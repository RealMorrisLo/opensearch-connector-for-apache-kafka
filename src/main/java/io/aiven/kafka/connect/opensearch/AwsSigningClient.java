package io.aiven.kafka.connect.opensearch;

import com.amazonaws.auth.*;
import org.apache.http.HttpRequestInterceptor;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestHighLevelClient;

public class AwsSigningClient {
  // Adds the interceptor to the OpenSearch REST client
  final static AWSCredentialsProvider credentialsProvider = new DefaultAWSCredentialsProviderChain();
  public RestHighLevelClient searchClient(OpensearchSinkConnectorConfig config) {
    final String SERVICE_NAME = "es";
    AWS4Signer signer = new AWS4Signer();
    signer.setServiceName(SERVICE_NAME);
    signer.setRegionName(OpensearchSinkConnectorConfig.CONNECTION_REGION_CONFIG);

    HttpRequestInterceptor interceptor =
        new AWSRequestSigningApacheInterceptor(SERVICE_NAME, signer, credentialsProvider);
    return new RestHighLevelClient(
        RestClient.builder(config.httpHosts())
            .setHttpClientConfigCallback(hacb -> hacb.addInterceptorLast(interceptor)));
  }
}
