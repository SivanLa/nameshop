// Copyright 2018 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.beam.spec11;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.http.HttpStatus.SC_OK;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.CharStreams;
import google.registry.util.Retrier;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.values.KV;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.HTTP;
import org.joda.time.Instant;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Utilities and Beam {@code PTransforms} for interacting with the SafeBrowsing API. */
public class SafeBrowsingTransforms {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** The URL to send SafeBrowsing API calls (POSTS) to. */
  private static final String SAFE_BROWSING_URL =
      "https://safebrowsing.googleapis.com/v4/threatMatches:find";

  /**
   * {@link DoFn} mapping a {@link DomainNameInfo} to its evaluation report from SafeBrowsing.
   *
   * <p>Refer to the Lookup API documentation for the request/response format and other details.
   *
   * @see <a href=https://developers.google.com/safe-browsing/v4/lookup-api>Lookup API</a>
   */
  static class EvaluateSafeBrowsingFn
      extends DoFn<DomainNameInfo, KV<DomainNameInfo, ThreatMatch>> {

    /**
     * Max number of urls we can check in a single query.
     *
     * <p>The actual max is 500, but we leave a small gap in case of concurrency errors.
     */
    private static final int BATCH_SIZE = 490;

    /** Provides the SafeBrowsing API key at runtime. */
    private final String apiKey;

    /**
     * Maps a domain name's {@code domainName} to its corresponding {@link DomainNameInfo} to
     * facilitate batching SafeBrowsing API requests.
     */
    private final Map<String, DomainNameInfo> domainNameInfoBuffer =
        new LinkedHashMap<>(BATCH_SIZE);

    /**
     * Provides the HTTP client we use to interact with the SafeBrowsing API.
     *
     * <p>This is a supplier to enable mocking out the connection in unit tests while maintaining a
     * serializable field.
     */
    private final Supplier<CloseableHttpClient> closeableHttpClientSupplier;

    /** Retries on receiving transient failures such as {@link IOException}. */
    private final Retrier retrier;

    /**
     * Constructs a {@link EvaluateSafeBrowsingFn} with a given API key.
     *
     * <p>We need to dual-cast the closeableHttpClientSupplier lambda because all {@code DoFn}
     * member variables need to be serializable. The (Supplier & Serializable) dual cast is safe
     * because class methods are generally serializable, especially a static function such as {@link
     * HttpClients#createDefault()}.
     */
    @SuppressWarnings("unchecked")
    EvaluateSafeBrowsingFn(String apiKey, Retrier retrier) {
      this.apiKey = apiKey;
      this.retrier = retrier;
      closeableHttpClientSupplier = (Supplier & Serializable) HttpClients::createDefault;
    }

    /**
     * Constructs a {@link EvaluateSafeBrowsingFn}, allowing us to swap out the HTTP client supplier
     * for testing.
     *
     * @param clientSupplier a serializable CloseableHttpClient supplier
     */
    @VisibleForTesting
    EvaluateSafeBrowsingFn(
        String apiKey, Retrier retrier, Supplier<CloseableHttpClient> clientSupplier) {
      this.apiKey = apiKey;
      this.retrier = retrier;
      closeableHttpClientSupplier = clientSupplier;
    }

    /** Evaluates any buffered {@link DomainNameInfo} objects upon completing the bundle. */
    @FinishBundle
    public void finishBundle(FinishBundleContext context) {
      if (!domainNameInfoBuffer.isEmpty()) {
        ImmutableSet<KV<DomainNameInfo, ThreatMatch>> results = evaluateAndFlush();
        results.forEach((kv) -> context.output(kv, Instant.now(), GlobalWindow.INSTANCE));
      }
    }

    /**
     * Buffers {@link DomainNameInfo} objects until we reach the batch size, then bulk-evaluate the
     * URLs with the SafeBrowsing API.
     */
    @ProcessElement
    public void processElement(ProcessContext context) {
      DomainNameInfo domainNameInfo = context.element();
      domainNameInfoBuffer.put(domainNameInfo.domainName(), domainNameInfo);
      if (domainNameInfoBuffer.size() >= BATCH_SIZE) {
        ImmutableSet<KV<DomainNameInfo, ThreatMatch>> results = evaluateAndFlush();
        results.forEach(context::output);
      }
    }

    /**
     * Evaluates all {@link DomainNameInfo} objects in the buffer and returns a list of key-value
     * pairs from {@link DomainNameInfo} to its SafeBrowsing report.
     *
     * <p>If a {@link DomainNameInfo} is safe according to the API, it will not emit a report.
     */
    private ImmutableSet<KV<DomainNameInfo, ThreatMatch>> evaluateAndFlush() {
      ImmutableSet.Builder<KV<DomainNameInfo, ThreatMatch>> resultBuilder =
          new ImmutableSet.Builder<>();
      try {
        URIBuilder uriBuilder = new URIBuilder(SAFE_BROWSING_URL);
        // Add the API key param
        uriBuilder.addParameter("key", apiKey);

        HttpPost httpPost = new HttpPost(uriBuilder.build());
        httpPost.addHeader(HTTP.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString());

        JSONObject requestBody = createRequestBody();
        httpPost.setEntity(new ByteArrayEntity(requestBody.toString().getBytes(UTF_8)));
        // Retry transient exceptions such as IOException
        retrier.callWithRetry(
            () -> {
              try (CloseableHttpClient client = closeableHttpClientSupplier.get();
                  CloseableHttpResponse response = client.execute(httpPost)) {
                processResponse(response, resultBuilder);
              }
            },
            IOException.class);
      } catch (URISyntaxException | JSONException e) {
        // Fail the pipeline on a parsing exception- this indicates the API likely changed.
        throw new RuntimeException("Caught parsing exception, failing pipeline.", e);
      } finally {
        // Flush the buffer
        domainNameInfoBuffer.clear();
      }
      return resultBuilder.build();
    }

    /** Creates a JSON object matching the request format for the SafeBrowsing API. */
    private JSONObject createRequestBody() throws JSONException {
      // Accumulate all domain names to evaluate.
      JSONArray threatArray = new JSONArray();
      for (String domainName : domainNameInfoBuffer.keySet()) {
        threatArray.put(new JSONObject().put("url", domainName));
      }
      // Construct the JSON request body
      return new JSONObject()
          .put(
              "client",
              new JSONObject().put("clientId", "domainregistry").put("clientVersion", "0.0.1"))
          .put(
              "threatInfo",
              new JSONObject()
                  .put(
                      "threatTypes",
                      new JSONArray()
                          .put("MALWARE")
                          .put("SOCIAL_ENGINEERING")
                          .put("UNWANTED_SOFTWARE"))
                  .put("platformTypes", new JSONArray().put("ANY_PLATFORM"))
                  .put("threatEntryTypes", new JSONArray().put("URL"))
                  .put("threatEntries", threatArray));
    }

    /**
     * Iterates through all threat matches in the API response and adds them to the {@code
     * resultBuilder}.
     */
    private void processResponse(
        CloseableHttpResponse response,
        ImmutableSet.Builder<KV<DomainNameInfo, ThreatMatch>> resultBuilder)
        throws JSONException, IOException {
      int statusCode = response.getStatusLine().getStatusCode();
      if (statusCode != SC_OK) {
        logger.atWarning().log("Got unexpected status code %s from response.", statusCode);
      } else {
        // Unpack the response body
        JSONObject responseBody =
            new JSONObject(
                CharStreams.toString(
                    new InputStreamReader(response.getEntity().getContent(), UTF_8)));
        logger.atInfo().log("Got response: %s", responseBody);
        if (responseBody.length() == 0) {
          logger.atInfo().log("Response was empty, no threats detected.");
        } else {
          // Emit all DomainNameInfos with their API results.
          JSONArray threatMatches = responseBody.getJSONArray("matches");
          for (int i = 0; i < threatMatches.length(); i++) {
            JSONObject match = threatMatches.getJSONObject(i);
            String url = match.getJSONObject("threat").getString("url");
            DomainNameInfo domainNameInfo = domainNameInfoBuffer.get(url);
            resultBuilder.add(
                KV.of(
                    domainNameInfo,
                    ThreatMatch.create(
                        match.getString("threatType"), domainNameInfo.domainName())));
          }
        }
      }
    }
  }
}
