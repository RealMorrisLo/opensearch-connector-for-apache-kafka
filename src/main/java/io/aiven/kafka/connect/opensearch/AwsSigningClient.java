package io.aiven.kafka.connect.opensearch;

import com.amazonaws.auth.AWS4Signer;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import org.apache.http.HttpRequestInterceptor;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestHighLevelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AwsSigningClient {
  // Adds the interceptor to the OpenSearch REST client

  private final Logger LOGGER = LoggerFactory.getLogger(AwsSigningClient.class);
  static final AWSCredentialsProvider credentialsProvider =
      new DefaultAWSCredentialsProviderChain();

  public RestHighLevelClient searchClient(OpensearchSinkConnectorConfig config) {
    final String SERVICE_NAME = "es";
    final AWS4Signer signer = new AWS4Signer();

    final Region region = Regions.getCurrentRegion();

    signer.setServiceName(SERVICE_NAME);
    signer.setRegionName(region.getName());
    LOGGER.info("Region Name: {}", region.getName());

    final HttpRequestInterceptor interceptor =
        new AWSRequestSigningApacheInterceptor(SERVICE_NAME, signer, credentialsProvider);
    return new RestHighLevelClient(
        RestClient.builder(config.httpHosts())
            .setHttpClientConfigCallback(hacb -> hacb.addInterceptorLast(interceptor)));
  }
}
