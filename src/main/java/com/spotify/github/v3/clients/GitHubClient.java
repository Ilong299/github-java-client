/*-
 * -\-\-
 * github-api
 * --
 * Copyright (C) 2016 - 2020 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.github.v3.clients;

import static java.util.concurrent.CompletableFuture.completedFuture;

import com.fasterxml.jackson.core.type.TypeReference;
import com.spotify.github.async.Async;
import com.spotify.github.http.HttpClient;
import com.spotify.github.http.HttpRequest;
import com.spotify.github.http.HttpResponse;
import com.spotify.github.http.ImmutableHttpRequest;
import com.spotify.github.http.okhttp.OkHttpHttpClient;
import com.spotify.github.jackson.Json;
import com.spotify.github.tracing.NoopTracer;
import com.spotify.github.tracing.Tracer;
import com.spotify.github.v3.Team;
import com.spotify.github.v3.User;
import com.spotify.github.v3.checks.AccessToken;
import com.spotify.github.v3.checks.Installation;
import com.spotify.github.v3.comment.Comment;
import com.spotify.github.v3.comment.CommentReaction;
import com.spotify.github.v3.exceptions.ReadOnlyRepositoryException;
import com.spotify.github.v3.exceptions.RequestNotOkException;
import com.spotify.github.v3.git.FileItem;
import com.spotify.github.v3.git.Reference;
import com.spotify.github.v3.orgs.TeamInvitation;
import com.spotify.github.v3.prs.PullRequestItem;
import com.spotify.github.v3.prs.Review;
import com.spotify.github.v3.prs.ReviewRequests;
import com.spotify.github.v3.repos.*;
import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import okhttp3.*;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GitHub client is a main communication entry point. Provides lower level communication
 * functionality as well as acts as a factory for the higher level API clients.
 */
public class GitHubClient {

  private static final int EXPIRY_MARGIN_IN_MINUTES = 5;
  private static final int HTTP_NOT_FOUND = 404;

  private Tracer tracer = NoopTracer.INSTANCE;

  static final Consumer<HttpResponse> IGNORE_RESPONSE_CONSUMER =
      (response) -> {
        if (response != null) {
          response.close();
        }
      };
  static final TypeReference<List<Comment>> LIST_COMMENT_TYPE_REFERENCE = new TypeReference<>() {};
  static final TypeReference<List<CommentReaction>> LIST_COMMENT_REACTION_TYPE_REFERENCE =
      new TypeReference<>() {};
  static final TypeReference<List<Repository>> LIST_REPOSITORY = new TypeReference<>() {};
  static final TypeReference<List<CommitItem>> LIST_COMMIT_TYPE_REFERENCE =
      new TypeReference<>() {};
  static final TypeReference<List<Review>> LIST_REVIEW_TYPE_REFERENCE = new TypeReference<>() {};
  static final TypeReference<ReviewRequests> LIST_REVIEW_REQUEST_TYPE_REFERENCE =
      new TypeReference<>() {};
  static final TypeReference<List<Status>> LIST_STATUS_TYPE_REFERENCE = new TypeReference<>() {};
  static final TypeReference<List<FolderContent>> LIST_FOLDERCONTENT_TYPE_REFERENCE =
      new TypeReference<>() {};
  static final TypeReference<List<PullRequestItem>> LIST_PR_TYPE_REFERENCE =
      new TypeReference<>() {};
  static final TypeReference<List<com.spotify.github.v3.prs.Comment>>
      LIST_PR_COMMENT_TYPE_REFERENCE = new TypeReference<>() {};
  static final TypeReference<List<Branch>> LIST_BRANCHES = new TypeReference<>() {};
  static final TypeReference<List<Reference>> LIST_REFERENCES = new TypeReference<>() {};
  static final TypeReference<List<RepositoryInvitation>> LIST_REPOSITORY_INVITATION =
      new TypeReference<>() {};

  static final TypeReference<List<Team>> LIST_TEAMS = new TypeReference<>() {};

  static final TypeReference<List<User>> LIST_TEAM_MEMBERS = new TypeReference<>() {};

  static final TypeReference<List<TeamInvitation>> LIST_PENDING_TEAM_INVITATIONS =
      new TypeReference<>() {};

  static final TypeReference<List<FileItem>> LIST_FILE_ITEMS = new TypeReference<>() {};

  private static final String GET_ACCESS_TOKEN_URL = "app/installations/%s/access_tokens";

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final int PERMANENT_REDIRECT = 301;
  private static final int TEMPORARY_REDIRECT = 307;
  private static final int FORBIDDEN = 403;

  private final URI baseUrl;

  private final Optional<URI> graphqlUrl;
  private final Json json = Json.create();
  private final HttpClient client;
  private Call.Factory callFactory;
  private final String token;

  private final byte[] privateKey;
  private final Integer appId;
  private final Integer installationId;

  private final Map<Integer, AccessToken> installationTokens;

  private GitHubClient(
      final HttpClient client,
      final URI baseUrl,
      final URI graphqlUrl,
      final String accessToken,
      final byte[] privateKey,
      final Integer appId,
      final Integer installationId) {
    this.baseUrl = baseUrl;
    this.graphqlUrl = Optional.ofNullable(graphqlUrl);
    this.token = accessToken;
    this.client = client;
    this.privateKey = privateKey;
    this.appId = appId;
    this.installationId = installationId;
    this.installationTokens = new ConcurrentHashMap<>();
  }

  private GitHubClient(
      final OkHttpClient client,
      final URI baseUrl,
      final URI graphqlUrl,
      final String accessToken,
      final byte[] privateKey,
      final Integer appId,
      final Integer installationId) {
    this.baseUrl = baseUrl;
    this.graphqlUrl = Optional.ofNullable(graphqlUrl);
    this.token = accessToken;
    this.client = new OkHttpHttpClient(client);
    this.privateKey = privateKey;
    this.appId = appId;
    this.installationId = installationId;
    this.installationTokens = new ConcurrentHashMap<>();
  }

  /**
   * Create a github api client with a given base URL and authorization token.
   *
   * @param baseUrl base URL
   * @param token authorization token
   * @return github api client
   */
  public static GitHubClient create(final URI baseUrl, final String token) {
    return new GitHubClient(new OkHttpClient(), baseUrl, null, token, null, null, null);
  }

  public static GitHubClient create(final URI baseUrl, final URI graphqlUri, final String token) {
    return new GitHubClient(new OkHttpClient(), baseUrl, graphqlUri, token, null, null, null);
  }

  /**
   * Create a github api client with a given base URL and a path to a key.
   *
   * @param baseUrl base URL
   * @param privateKey the private key PEM file
   * @param appId the github app ID
   * @return github api client
   */
  public static GitHubClient create(final URI baseUrl, final File privateKey, final Integer appId) {
    return createOrThrow(new OkHttpClient(), baseUrl, null, privateKey, appId, null);
  }

  /**
   * Create a github api client with a given base URL and a path to a key.
   *
   * @param baseUrl base URL
   * @param privateKey the private key as byte array
   * @param appId the github app ID
   * @return github api client
   */
  public static GitHubClient create(
      final URI baseUrl, final byte[] privateKey, final Integer appId) {
    return new GitHubClient(new OkHttpClient(), baseUrl, null, null, privateKey, appId, null);
  }

  /**
   * Create a github api client with a given base URL and a path to a key.
   *
   * @param baseUrl base URL
   * @param privateKey the private key PEM file
   * @param appId the github app ID
   * @param installationId the installationID to be authenticated as
   * @return github api client
   */
  public static GitHubClient create(
      final URI baseUrl, final File privateKey, final Integer appId, final Integer installationId) {
    return createOrThrow(new OkHttpClient(), baseUrl, null, privateKey, appId, installationId);
  }

  /**
   * Create a github api client with a given base URL and a path to a key.
   *
   * @param baseUrl base URL
   * @param privateKey the private key as byte array
   * @param appId the github app ID
   * @param installationId the installationID to be authenticated as
   * @return github api client
   */
  public static GitHubClient create(
      final URI baseUrl,
      final byte[] privateKey,
      final Integer appId,
      final Integer installationId) {
    return new GitHubClient(
        new OkHttpClient(), baseUrl, null, null, privateKey, appId, installationId);
  }

  /**
   * Create a github api client with a given base URL and a path to a key.
   *
   * @param httpClient an instance of OkHttpClient
   * @param baseUrl base URL
   * @param privateKey the private key PEM file
   * @param appId the github app ID
   * @return github api client
   */
  public static GitHubClient create(
      final OkHttpClient httpClient,
      final URI baseUrl,
      final File privateKey,
      final Integer appId) {
    return createOrThrow(httpClient, baseUrl, null, privateKey, appId, null);
  }

  /**
   * Create a github api client with a given base URL and a path to a key.
   *
   * @param httpClient an instance of OkHttpClient
   * @param baseUrl base URL
   * @param privateKey the private key PEM file
   * @param appId the github app ID
   * @return github api client
   */
  public static GitHubClient create(
      final OkHttpClient httpClient,
      final URI baseUrl,
      final URI graphqlUrl,
      final File privateKey,
      final Integer appId) {
    return createOrThrow(httpClient, baseUrl, graphqlUrl, privateKey, appId, null);
  }

  /**
   * Create a github api client with a given base URL and a path to a key.
   *
   * @param httpClient an instance of OkHttpClient
   * @param baseUrl base URL
   * @param privateKey the private key as byte array
   * @param appId the github app ID
   * @return github api client
   */
  public static GitHubClient create(
      final OkHttpClient httpClient,
      final URI baseUrl,
      final byte[] privateKey,
      final Integer appId) {
    return new GitHubClient(httpClient, baseUrl, null, null, privateKey, appId, null);
  }

  /**
   * Create a github api client with a given base URL and a path to a key.
   *
   * @param httpClient an instance of OkHttpClient
   * @param baseUrl base URL
   * @param privateKey the private key PEM file
   * @param appId the github app ID
   * @return github api client
   */
  public static GitHubClient create(
      final OkHttpClient httpClient,
      final URI baseUrl,
      final File privateKey,
      final Integer appId,
      final Integer installationId) {
    return createOrThrow(httpClient, baseUrl, null, privateKey, appId, installationId);
  }

  /**
   * Create a github api client with a given base URL and a path to a key.
   *
   * @param httpClient an instance of OkHttpClient
   * @param baseUrl base URL
   * @param privateKey the private key as byte array
   * @param appId the github app ID
   * @return github api client
   */
  public static GitHubClient create(
      final OkHttpClient httpClient,
      final URI baseUrl,
      final byte[] privateKey,
      final Integer appId,
      final Integer installationId) {
    return new GitHubClient(httpClient, baseUrl, null, null, privateKey, appId, installationId);
  }

  /**
   * Create a github api client with a given base URL and authorization token.
   *
   * @param httpClient an instance of OkHttpClient
   * @param baseUrl base URL
   * @param token authorization token
   * @return github api client
   */
  public static GitHubClient create(
      final OkHttpClient httpClient, final URI baseUrl, final String token) {
    return new GitHubClient(httpClient, baseUrl, null, token, null, null, null);
  }

  public static GitHubClient create(
      final OkHttpClient httpClient, final URI baseUrl, final URI graphqlUrl, final String token) {
    return new GitHubClient(httpClient, baseUrl, graphqlUrl, token, null, null, null);
  }

  /**
   * Create a github api client with a given base URL and a path to a key.
   *
   * @param httpClient an instance of OkHttpClient
   * @param baseUrl base URL
   * @param privateKey the private key PEM file
   * @param appId the github app ID
   * @return github api client
   */
  public static GitHubClient create(
      final HttpClient httpClient, final URI baseUrl, final File privateKey, final Integer appId) {
    return createOrThrow(httpClient, baseUrl, null, privateKey, appId, null);
  }

  /**
   * Create a github api client with a given base URL and a path to a key.
   *
   * @param httpClient an instance of OkHttpClient
   * @param baseUrl base URL
   * @param privateKey the private key PEM file
   * @param appId the github app ID
   * @return github api client
   */
  public static GitHubClient create(
      final HttpClient httpClient,
      final URI baseUrl,
      final URI graphqlUrl,
      final File privateKey,
      final Integer appId) {
    return createOrThrow(httpClient, baseUrl, graphqlUrl, privateKey, appId, null);
  }

  /**
   * Create a github api client with a given base URL and a path to a key.
   *
   * @param httpClient an instance of OkHttpClient
   * @param baseUrl base URL
   * @param privateKey the private key as byte array
   * @param appId the github app ID
   * @return github api client
   */
  public static GitHubClient create(
      final HttpClient httpClient,
      final URI baseUrl,
      final byte[] privateKey,
      final Integer appId) {
    return new GitHubClient(httpClient, baseUrl, null, null, privateKey, appId, null);
  }

  /**
   * Create a github api client with a given base URL and a path to a key.
   *
   * @param httpClient an instance of OkHttpClient
   * @param baseUrl base URL
   * @param privateKey the private key PEM file
   * @param appId the github app ID
   * @return github api client
   */
  public static GitHubClient create(
      final HttpClient httpClient,
      final URI baseUrl,
      final File privateKey,
      final Integer appId,
      final Integer installationId) {
    return createOrThrow(httpClient, baseUrl, null, privateKey, appId, installationId);
  }

  /**
   * Create a github api client with a given base URL and a path to a key.
   *
   * @param httpClient an instance of OkHttpClient
   * @param baseUrl base URL
   * @param privateKey the private key as byte array
   * @param appId the github app ID
   * @return github api client
   */
  public static GitHubClient create(
      final HttpClient httpClient,
      final URI baseUrl,
      final byte[] privateKey,
      final Integer appId,
      final Integer installationId) {
    return new GitHubClient(httpClient, baseUrl, null, null, privateKey, appId, installationId);
  }

  /**
   * Create a github api client with a given base URL and authorization token.
   *
   * @param httpClient an instance of OkHttpClient
   * @param baseUrl base URL
   * @param token authorization token
   * @return github api client
   */
  public static GitHubClient create(
      final HttpClient httpClient, final URI baseUrl, final String token) {
    return new GitHubClient(httpClient, baseUrl, null, token, null, null, null);
  }

  public static GitHubClient create(
      final HttpClient httpClient, final URI baseUrl, final URI graphqlUrl, final String token) {
    return new GitHubClient(httpClient, baseUrl, graphqlUrl, token, null, null, null);
  }

  /**
   * Receives a github client and scopes it to a certain installation ID.
   *
   * @param client the github client with a valid private key
   * @param installationId the installation ID to be scoped
   * @return github api client
   */
  public static GitHubClient scopeForInstallationId(
      final GitHubClient client, final int installationId) {
    if (client.getPrivateKey().isEmpty()) {
      throw new RuntimeException("Installation ID scoped client needs a private key");
    }
    return new GitHubClient(
        client.client,
        client.baseUrl,
        null,
        null,
        client.getPrivateKey().get(),
        client.appId,
        installationId);
  }

  public GitHubClient withScopeForInstallationId(final int installationId) {
    if (Optional.ofNullable(privateKey).isEmpty()) {
      throw new RuntimeException("Installation ID scoped client needs a private key");
    }
    return new GitHubClient(
        client, baseUrl, graphqlUrl.orElse(null), null, privateKey, appId, installationId);
  }

  /**
   * This is for clients authenticated as a GitHub App: when performing operations, the
   * "installation" of the App must be specified. This returns a {@code GitHubClient} that has been
   * scoped to the user's/organization's installation of the app, if any.
   */
  public CompletionStage<Optional<GitHubClient>> asAppScopedClient(final String owner) {
    return Async.exceptionallyCompose(
            this.createOrganisationClient(owner)
                .createGithubAppClient()
                .getInstallation()
                .thenApply(Installation::id),
            e -> {
              if (e.getCause() instanceof RequestNotOkException
                  && ((RequestNotOkException) e.getCause()).statusCode() == HTTP_NOT_FOUND) {
                return this.createUserClient(owner)
                    .createGithubAppClient()
                    .getUserInstallation()
                    .thenApply(Installation::id);
              }
              return CompletableFuture.failedFuture(e);
            })
        .thenApply(id -> Optional.of(this.withScopeForInstallationId(id)))
        .exceptionally(
            e -> {
              if (e.getCause() instanceof RequestNotOkException
                  && ((RequestNotOkException) e.getCause()).statusCode() == HTTP_NOT_FOUND) {
                return Optional.empty();
              }
              throw new RuntimeException(e);
            });
  }

  public GitHubClient withTracer(final Tracer tracer) {
    this.tracer = tracer;
    this.client.setTracer(tracer);
    return this;
  }

  public Optional<byte[]> getPrivateKey() {
    return Optional.ofNullable(privateKey);
  }

  public Optional<String> getAccessToken() {
    return Optional.ofNullable(token);
  }

  /**
   * Create a repository API client
   *
   * @param owner repository owner
   * @param repo repository name
   * @return repository API client
   */
  public RepositoryClient createRepositoryClient(final String owner, final String repo) {
    return RepositoryClient.create(this, owner, repo);
  }

  /**
   * Create a GitData API client
   *
   * @param owner repository owner
   * @param repo repository name
   * @return GitData API client
   */
  public GitDataClient createGitDataClient(final String owner, final String repo) {
    return GitDataClient.create(this, owner, repo);
  }

  /**
   * Create search API client
   *
   * @return search API client
   */
  public SearchClient createSearchClient() {
    return SearchClient.create(this);
  }

  /**
   * Create a checks API client
   *
   * @param owner repository owner
   * @param repo repository name
   * @return checks API client
   */
  public ChecksClient createChecksClient(final String owner, final String repo) {
    return ChecksClient.create(this, owner, repo);
  }

  /**
   * Create organisation API client
   *
   * @return organisation API client
   */
  public OrganisationClient createOrganisationClient(final String org) {
    return OrganisationClient.create(this, org);
  }

  /**
   * Create user API client
   *
   * @return user API client
   */
  public UserClient createUserClient(final String owner) {
    return UserClient.create(this, owner);
  }

  Json json() {
    return json;
  }

  /**
   * Make a http GET request for the given path on the server
   *
   * @param path relative to the GitHub base url
   * @return response body as a String
   */
  CompletableFuture<HttpResponse> request(final String path) {
    return call("GET", path);
  }

  /**
   * Make a http GET request for the given path on the server
   *
   * @param path relative to the GitHub base url
   * @param extraHeaders extra github headers to be added to the call
   * @return a reader of response body
   */
  CompletableFuture<HttpResponse> request(
      final String path, final Map<String, String> extraHeaders) {
    return call("GET", path, extraHeaders);
  }

  /**
   * Make a http GET request for the given path on the server
   *
   * @param path relative to the GitHub base url
   * @return body deserialized as provided type
   */
  <T> CompletableFuture<T> request(final String path, final Class<T> clazz) {
    return call(path)
        .thenApply(response -> json().fromJsonUncheckedNotNull(response.bodyString(), clazz));
  }

  /**
   * Make a http GET request for the given path on the server
   *
   * @param path relative to the GitHub base url
   * @param extraHeaders extra github headers to be added to the call
   * @return body deserialized as provided type
   */
  <T> CompletableFuture<T> request(
      final String path, final Class<T> clazz, final Map<String, String> extraHeaders) {
    return call("GET", path, null, extraHeaders)
        .thenApply(response -> json().fromJsonUncheckedNotNull(response.bodyString(), clazz));
  }

  /**
   * Make a http request for the given path on the GitHub server.
   *
   * @param path relative to the GitHub base url
   * @param extraHeaders extra github headers to be added to the call
   * @return body deserialized as provided type
   */
  <T> CompletableFuture<T> request(
      final String path,
      final TypeReference<T> typeReference,
      final Map<String, String> extraHeaders) {
    return call("GET", path, null, extraHeaders)
        .thenApply(
            response -> json().fromJsonUncheckedNotNull(response.bodyString(), typeReference));
  }

  /**
   * Make a http request for the given path on the GitHub server.
   *
   * @param path relative to the GitHub base url
   * @return body deserialized as provided type
   */
  <T> CompletableFuture<T> request(final String path, final TypeReference<T> typeReference) {
    return call(path)
        .thenApply(
            response -> json().fromJsonUncheckedNotNull(response.bodyString(), typeReference));
  }

  /**
   * Make a http POST request for the given path with provided JSON body.
   *
   * @param path relative to the GitHub base url
   * @param data request body as stringified JSON
   * @return response body as String
   */
  CompletableFuture<HttpResponse> post(final String path, final String data) {
    return call("POST", path, data);
  }

  /**
   * Make a http POST request for the given path with provided JSON body.
   *
   * @param path relative to the GitHub base url
   * @param data request body as stringified JSON
   * @param extraHeaders
   * @return response body as String
   */
  CompletableFuture<HttpResponse> post(
      final String path, final String data, final Map<String, String> extraHeaders) {
    return call("POST", path, data, extraHeaders);
  }

  /**
   * Make a http POST request for the given path with provided JSON body.
   *
   * @param path relative to the GitHub base url
   * @param data request body as stringified JSON
   * @param clazz class to cast response as
   * @param extraHeaders
   * @return response body deserialized as provided class
   */
  <T> CompletableFuture<T> post(
      final String path,
      final String data,
      final Class<T> clazz,
      final Map<String, String> extraHeaders) {
    return post(path, data, extraHeaders)
        .thenApply(response -> json().fromJsonUncheckedNotNull(response.bodyString(), clazz));
  }

  /**
   * Make a http POST request for the given path with provided JSON body.
   *
   * @param path relative to the GitHub base url
   * @param data request body as stringified JSON
   * @param clazz class to cast response as
   * @return response body deserialized as provided class
   */
  <T> CompletableFuture<T> post(final String path, final String data, final Class<T> clazz) {
    return post(path, data)
        .thenApply(response -> json().fromJsonUncheckedNotNull(response.bodyString(), clazz));
  }

  /**
   * Make a POST request to the graphql endpoint of GitHub
   *
   * @param data request body as stringified JSON
   * @return response
   * @see
   *     "https://docs.github.com/en/enterprise-server@3.9/graphql/guides/forming-calls-with-graphql#communicating-with-graphql"
   */
  public CompletableFuture<HttpResponse> postGraphql(final String data) {
    return graphqlRequestBuilder()
        .thenCompose(
            requestBuilder -> {
              final HttpRequest request = requestBuilder.method("POST").body(data).build();
              log.info("Making POST request to {}", request.url());
              return call(request);
            });
  }

  /**
   * Make a http PUT request for the given path with provided JSON body.
   *
   * @param path relative to the GitHub base url
   * @param data request body as stringified JSON
   * @return response body as String
   */
  CompletableFuture<HttpResponse> put(final String path, final String data) {
    return call("PUT", path, data);
  }

  /**
   * Make a HTTP PUT request for the given path with provided JSON body.
   *
   * @param path relative to the GitHub base url
   * @param data request body as stringified JSON
   * @param clazz class to cast response as
   * @return response body deserialized as provided class
   */
  <T> CompletableFuture<T> put(final String path, final String data, final Class<T> clazz) {
    return put(path, data)
        .thenApply(response -> json().fromJsonUncheckedNotNull(response.bodyString(), clazz));
  }

  /**
   * Make a http PATCH request for the given path with provided JSON body.
   *
   * @param path relative to the GitHub base url
   * @param data request body as stringified JSON
   * @return response body as String
   */
  CompletableFuture<HttpResponse> patch(final String path, final String data) {
    return call("PATCH", path, data);
  }

  /**
   * Make a http PATCH request for the given path with provided JSON body.
   *
   * @param path relative to the GitHub base url
   * @param data request body as stringified JSON
   * @param clazz class to cast response as
   * @return response body deserialized as provided class
   */
  <T> CompletableFuture<T> patch(final String path, final String data, final Class<T> clazz) {
    return patch(path, data)
        .thenApply(response -> json().fromJsonUncheckedNotNull(response.bodyString(), clazz));
  }

  /**
   * Make a http PATCH request for the given path with provided JSON body
   *
   * @param path relative to the GitHub base url
   * @param data request body as stringified JSON
   * @param clazz class to cast response as
   * @return response body deserialized as provided class
   */
  <T> CompletableFuture<T> patch(
      final String path,
      final String data,
      final Class<T> clazz,
      final Map<String, String> extraHeaders) {
    return call("PATCH", path, data, extraHeaders)
        .thenApply(response -> json().fromJsonUncheckedNotNull(response.bodyString(), clazz));
  }

  /**
   * Make a http DELETE request for the given path.
   *
   * @param path relative to the GitHub base url
   * @return response body as String
   */
  CompletableFuture<HttpResponse> delete(final String path) {
    return call("DELETE", path);
  }

  /**
   * Make a http DELETE request for the given path.
   *
   * @param path relative to the GitHub base url
   * @param data request body as stringified JSON
   * @return response body as String
   */
  CompletableFuture<HttpResponse> delete(final String path, final String data) {
    return call("DELETE", path, data);
  }

  /**
   * Make a http DELETE request for the given path.
   *
   * @param path relative to the GitHub base url
   * @return response body as String
   */
  private CompletableFuture<HttpResponse> call(final String path) {
    return call("GET", path, null, null);
  }

  /**
   * Make a http request for the given path on the GitHub server.
   *
   * @param method HTTP method
   * @param path relative to the GitHub base url
   * @return response body as String
   */
  private CompletableFuture<HttpResponse> call(final String method, final String path) {
    return call(method, path, null, null);
  }

  /**
   * Make a http request for the given path on the GitHub server.
   *
   * @param method HTTP method
   * @param path relative to the GitHub base url
   * @param extraHeaders extra github headers to be added to the call
   * @return response body as String
   */
  private CompletableFuture<HttpResponse> call(
      final String method, final String path, final Map<String, String> extraHeaders) {
    return call(method, path, null, extraHeaders);
  }

  /*
   * Make a http request for the given path on the GitHub server.
   *
   * @param method HTTP method
   * @param path relative to the GitHub base url
   * @param data request body as stringified JSON
   * @return response body as String
   */
  private CompletableFuture<HttpResponse> call(
      final String method, final String path, final String data) {
    return call(method, path, data, null);
  }

  /**
   * Make a http request for the given path on the GitHub server.
   *
   * @param method HTTP method
   * @param path relative to the GitHub base url
   * @param data request body as stringified JSON
   * @param extraHeaders extra github headers to be added to the call
   * @return response body as String
   */
  private CompletableFuture<HttpResponse> call(
      final String method,
      final String path,
      @Nullable final String data,
      @Nullable final Map<String, String> extraHeaders) {
    return requestBuilder(path)
        .thenCompose(
            requestBuilder -> {
              final ImmutableHttpRequest.Builder builder = requestBuilder.method(method);
              if (data != null) {
                builder.body(data);
              }
              final HttpRequest request =
                  extraHeaders == null || extraHeaders.isEmpty()
                      ? builder.build()
                      : toHttpRequestHeaders(builder, extraHeaders).build();
              log.debug("Making {} request to {}", method, request.url().toString());
              return call(request);
            });
  }

  /**
   * Create a URL for a given path to this GitHub server.
   *
   * @param path relative URI
   * @return URL to path on this server
   */
  String urlFor(final String path) {
    return baseUrl.toString().replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "");
  }

  /**
   * Adds extra headers to the Request Builder
   *
   * @param builder the request builder
   * @param extraHeaders the extra headers to be added
   * @return the request builder with the extra headers
   */
  private ImmutableHttpRequest.Builder toHttpRequestHeaders(
      final ImmutableHttpRequest.Builder builder, final Map<String, String> extraHeaders) {
    HttpRequest request = builder.build();

    extraHeaders.forEach(
        (headerKey, headerValue) -> {
          if (request.headers().containsKey(headerKey)) {
            List<String> headers = new ArrayList<>(request.headers().get(headerKey));
            headers.add(headerValue);
            builder.putHeaders(headerKey, headers);
          } else {
            builder.putHeaders(headerKey, List.of(headerValue));
          }
        });
    return builder;
  }

  /*
   * Create a Request Builder for this GitHub GraphQL server.
   *
   * @return GraphQL Request Builder
   */
  private CompletableFuture<ImmutableHttpRequest.Builder> graphqlRequestBuilder() {
    URI url = graphqlUrl.orElseThrow(() -> new IllegalStateException("No graphql url set"));
    return requestBuilder("/graphql")
        .thenApply(requestBuilder -> requestBuilder.url(url.toString()));
  }

  /*
   * Create a Request Builder for this GitHub server.
   *
   * @param path relative URI
   * @return Request Builder
   */
  private CompletableFuture<ImmutableHttpRequest.Builder> requestBuilder(final String path) {
    return getAuthorizationHeader(path)
        .thenApply(
            authHeader ->
                ImmutableHttpRequest.builder()
                    .url(urlFor(path))
                    .method("GET")
                    .body("")
                    .putHeaders(HttpHeaders.ACCEPT, List.of(MediaType.APPLICATION_JSON))
                    .putHeaders(HttpHeaders.CONTENT_TYPE, List.of(MediaType.APPLICATION_JSON))
                    .putHeaders(HttpHeaders.AUTHORIZATION, List.of(authHeader)));
  }

  /*
   * Check if the GraphQL API is enabled for this client.
   *
   * @return true if the GraphQL API is enabled, false otherwise
   */
  public boolean isGraphqlEnabled() {
    return graphqlUrl.isPresent();
  }

  /*
   Generates the Authentication header, given the API endpoint and the credentials provided.

   <p>GitHub Requests can be authenticated in 3 different ways.
   (1) Regular, static access token;
   (2) JWT Token, generated from a private key. Used in GitHub Apps;
   (3) Installation Token, generated from the JWT token. Also used in GitHub Apps.
  */
  private CompletableFuture<String> getAuthorizationHeader(final String path) {
    if (isJwtRequest(path) && getPrivateKey().isEmpty()) {
      throw new IllegalStateException("This endpoint needs a client with a private key for an App");
    }
    if (getAccessToken().isPresent()) {
      return completedFuture(String.format("token %s", token));
    } else if (getPrivateKey().isPresent()) {
      final String jwtToken;
      try {
        jwtToken = JwtTokenIssuer.fromPrivateKey(privateKey).getToken(appId);
      } catch (Exception e) {
        throw new RuntimeException("There was an error generating JWT token", e);
      }
      if (isJwtRequest(path)) {
        return completedFuture(String.format("Bearer %s", jwtToken));
      }
      if (installationId == null) {
        throw new RuntimeException("This endpoint needs a client with an installation ID");
      }
      try {
        return getInstallationToken(jwtToken, installationId)
            .thenApply(token -> String.format("token %s", token))
            .exceptionally(
                ex -> {
                  throw new RuntimeException("Could not generate access token for github app", ex);
                });
      } catch (Exception e) {
        throw new RuntimeException("Could not generate access token for github app", e);
      }
    }
    throw new RuntimeException("Not possible to authenticate. ");
  }

  private boolean isJwtRequest(final String path) {
    return path.startsWith("/app/installation") || path.endsWith("installation");
  }

  /**
   * Fetches installation token from the cache or from the server if it is expired.
   *
   * @param jwtToken the JWT token
   * @param installationId the installation ID
   * @return a CompletableFuture with the installation token
   */
  private CompletableFuture<String> getInstallationToken(
      final String jwtToken, final int installationId) {

    AccessToken installationToken = installationTokens.get(installationId);

    if (installationToken == null || isExpired(installationToken)) {
      log.info(
          "GitHub token for installation {} is either expired or null. Trying to get a new one.",
          installationId);
      return generateInstallationToken(jwtToken, installationId)
          .thenApply(
              accessToken -> {
                installationTokens.put(installationId, accessToken);
                return accessToken.token();
              });
    }
    return completedFuture(installationToken.token());
  }

  /**
   * Check if the token is expired.
   *
   * @param token the access token
   * @return true if the token is expired, false otherwise
   */
  private boolean isExpired(final AccessToken token) {
    // Adds a few minutes to avoid making calls with an expired token due to clock differences
    return token.expiresAt().isBefore(ZonedDateTime.now().plusMinutes(EXPIRY_MARGIN_IN_MINUTES));
  }

  /**
   * Generates the installation token for a given installation ID.
   *
   * @param jwtToken the JWT token
   * @param installationId the installation ID
   * @return a CompletableFuture with the access token
   */
  private CompletableFuture<AccessToken> generateInstallationToken(
      final String jwtToken, final int installationId) {
    log.info("Got JWT Token. Now getting GitHub access_token for installation {}", installationId);
    final String url = String.format(urlFor(GET_ACCESS_TOKEN_URL), installationId);
    final HttpRequest request =
        ImmutableHttpRequest.builder()
            .url(url)
            .putHeaders("Accept", List.of("application/vnd.github.machine-man-preview+json"))
            .putHeaders("Authorization", List.of("Bearer " + jwtToken))
            .method("POST")
            .body("")
            .build();

    return this.client
        .send(request)
        .thenApply(
            response -> {
              if (!response.isSuccessful()) {
                throw new RuntimeException(
                    String.format(
                        "Got non-2xx status %s when getting an access token from GitHub: %s",
                        response.statusCode(), response.statusMessage()));
              }

              if (response.bodyString() == null) {
                throw new RuntimeException(
                    String.format(
                        "Got empty response body when getting an access token from GitHub, HTTP"
                            + " status was: %s",
                        response.statusMessage()));
              }
              final String text = response.bodyString();
              try {
                return Json.create().fromJson(text, AccessToken.class);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            })
        .toCompletableFuture();
  }

  private CompletableFuture<HttpResponse> call(final HttpRequest httpRequest) {
    return this.client
        .send(httpRequest)
        .thenCompose(httpResponse -> handleResponse(httpRequest, httpResponse));
  }

  /**
   * Handle the response from the server. If the response is a redirect, redo the request with the
   * new URL.
   *
   * @param httpRequest the original request
   * @param httpResponse the response from the server
   * @return a CompletableFuture with the processed response
   */
  private CompletableFuture<HttpResponse> handleResponse(
      final HttpRequest httpRequest, final HttpResponse httpResponse) {
    final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
    // avoid multiple redirects
    final AtomicBoolean redirected = new AtomicBoolean(false);
    processPossibleRedirects(httpResponse, redirected)
        .handle(
            (res, ex) -> {
              if (Objects.nonNull(ex)) {
                future.completeExceptionally(ex);
              } else if (!res.isSuccessful()) {
                try {
                  future.completeExceptionally(mapException(httpRequest, res));
                } catch (final Throwable e) {
                  future.completeExceptionally(e);
                }
              } else {
                future.complete(res);
              }
              return res;
            })
        .join();
    return future;
  }

  /**
   * Map the exception to a specific type based on the response status code.
   *
   * @param httpRequest the original request
   * @param httpResponse the response from the server
   * @return a RequestNotOkException with the appropriate type
   */
  private RequestNotOkException mapException(
      final HttpRequest httpRequest, final HttpResponse httpResponse) throws IOException {
    String bodyString = Optional.ofNullable(httpResponse.bodyString()).orElse("");
    Map<String, List<String>> headersMap = httpResponse.headers();

    if (httpResponse.statusCode() == FORBIDDEN) {
      if (bodyString.contains("Repository was archived so is read-only")) {
        return new ReadOnlyRepositoryException(
            httpRequest.method(),
            URI.create(httpRequest.url()).getPath(),
            httpResponse.statusCode(),
            bodyString,
            headersMap);
      }
    }

    return new RequestNotOkException(
        httpRequest.method(),
        URI.create(httpRequest.url()).getPath(),
        httpResponse.statusCode(),
        bodyString,
        headersMap);
  }

  /**
   * Process possible redirects. If the response is a redirect, redo the request with the new URL.
   *
   * @param response the response to process
   * @param redirected a flag to indicate if a redirect has already occurred
   * @return a CompletableFuture with the processed response
   */
  CompletableFuture<HttpResponse> processPossibleRedirects(
      final HttpResponse response, final AtomicBoolean redirected) {
    if (response.statusCode() >= PERMANENT_REDIRECT
        && response.statusCode() <= TEMPORARY_REDIRECT
        && !redirected.get()) {
      redirected.set(true);
      // redo the same request with a new URL
      final String newLocation = response.headers().get("Location").get(0);
      return requestBuilder(newLocation)
          .thenCompose(
              requestBuilder -> {
                HttpRequest request =
                    requestBuilder
                        .url(newLocation)
                        .method(response.request().method())
                        .body(response.request().body())
                        .build();
                // Do the new call and complete the original future when the new call completes
                return call(request);
              });
    }

    return completedFuture(response);
  }

  /** Wrapper to Constructors that expose File object for the privateKey argument */
  private static GitHubClient createOrThrow(
      final OkHttpClient httpClient,
      final URI baseUrl,
      final URI graphqlUrl,
      final File privateKey,
      final Integer appId,
      final Integer installationId) {
    try {
      return new GitHubClient(
          httpClient,
          baseUrl,
          graphqlUrl,
          null,
          FileUtils.readFileToByteArray(privateKey),
          appId,
          installationId);
    } catch (IOException e) {
      throw new RuntimeException("There was an error generating JWT token", e);
    }
  }

  /** Wrapper to Constructors that expose File object for the privateKey argument */
  private static GitHubClient createOrThrow(
      final HttpClient httpClient,
      final URI baseUrl,
      final URI graphqlUrl,
      final File privateKey,
      final Integer appId,
      final Integer installationId) {
    try {
      return new GitHubClient(
          httpClient,
          baseUrl,
          graphqlUrl,
          null,
          FileUtils.readFileToByteArray(privateKey),
          appId,
          installationId);
    } catch (IOException e) {
      throw new RuntimeException("There was an error generating JWT token", e);
    }
  }
}
