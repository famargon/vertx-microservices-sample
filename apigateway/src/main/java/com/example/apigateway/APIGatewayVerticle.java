package com.example.apigateway;

import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.servicediscovery.Record;
import io.vertx.servicediscovery.ServiceDiscovery;
import io.vertx.servicediscovery.ServiceDiscoveryOptions;
import io.vertx.servicediscovery.types.HttpEndpoint;

public class APIGatewayVerticle extends AbstractVerticle {

	protected ServiceDiscovery discovery;
	private CircuitBreaker circuitBreaker;

	@Override
	public void start(Future<Void> future) throws Exception {
		discovery = ServiceDiscovery.create(vertx);
		
		circuitBreaker = CircuitBreaker.create("circuit-breaker", vertx, new CircuitBreakerOptions().setMaxFailures(5)
				.setTimeout(10000L).setFallbackOnFailure(true).setResetTimeout(30000L));

		// get HTTP host and port from configuration, or use default value
		String host = config().getString("api.gateway.http.address", "localhost");
		int port = config().getInteger("api.gateway.http.port", 8787); // (1)

		Router router = Router.router(vertx); // (2)

		// body handler
		router.route().handler(BodyHandler.create()); // (4)

		// api dispatcher
		router.route("/*").handler(this::dispatchRequests); // (10)

		// create http server
		vertx.createHttpServer().requestHandler(router::accept).listen(port, host, ar -> { // (14)
			if (ar.succeeded()) {
				publishApiGateway(host, port);
				future.complete();
			} else {
				future.fail(ar.cause());
			}
		});

	}

	private void publishApiGateway(String host, int port){
		discovery.publish(HttpEndpoint.createRecord("api-gateway", host, port, "/"), res -> {
			if(!res.succeeded()){
				System.out.println(res.cause());
			}
		});
	}
	
	private Future<List<Record>> getAllEndpoints() {
		Future<List<Record>> future = Future.future();
		discovery.getRecords(record -> record.getType().equals(HttpEndpoint.TYPE), future.completer());
		return future;
	}

	private void dispatchRequests(RoutingContext context) {
		// run with circuit breaker in order to deal with failure
		circuitBreaker.execute(future -> getAllEndpoints().setHandler(ar -> routeRequest(context, future, ar))
		).setHandler(ar -> {
			if (ar.failed()) {
				context.response().setStatusCode(502).putHeader("content-type", "application/json")
						.end(new JsonObject().put("error", "bad_gateway").encodePrettily());
				System.out.println(ar.cause());
			}
		});
	}

	private void routeRequest(RoutingContext context, Future<Object> future, AsyncResult<List<Record>> ar) {
		if (ar.succeeded()) {
			List<Record> recordList = ar.result();

			for(Record r : recordList){
				System.out.println(r.getName() + " "+ r.getMetadata().encodePrettily());
			}
			// get relative path and retrieve prefix to dispatch client
			String path = context.request().uri();

			if (path.length() == 1) {
				notFound(context, "invalid request, this is an api gateway");
				future.complete();
				return;
			}
			String prefix = StringUtils.substringBefore(StringUtils.substringAfter(path, "/"),"/");
			// generate new relative path
			String newPath = path.substring(prefix.length()+1);
			System.out.println("new path " + newPath);
			// get one relevant HTTP client, may not exist
			Optional<Record> client = recordList.stream()
					.filter(record -> record.getMetadata().getString("api.name") != null && record.getMetadata().getString("api.name").equals(prefix))
					.findAny(); // (4) simple load balance

			if (client.isPresent()) {
				doDispatch(context, newPath, discovery.getReference(client.get()).get(), future); // (5)
			} else {
				notFound(context, "not found"); // (6)
				future.complete();
			}
		} else {
			future.fail(ar.cause()); // (8)
		}
	}

	private void doDispatch(RoutingContext context, String path, HttpClient client, Future<Object> cbFuture) {

		HttpClientRequest req = client.request(context.request().method(), path, response -> {
			response.bodyHandler(body -> {
				if (response.statusCode() >= 500) { // api endpoint server
													// error, circuit breaker
													// should fail
					cbFuture.fail(response.statusCode() + ": " + body.toString());
				} else {
					HttpServerResponse toRsp = context.response().setStatusCode(response.statusCode());
					response.headers().forEach(header -> {
						toRsp.putHeader(header.getKey(), header.getValue());
					});
					// send response
					toRsp.end(body);
					cbFuture.complete();
				}
				ServiceDiscovery.releaseServiceObject(discovery, client);
			});
		});
		context.request().headers().forEach(header -> {
			req.putHeader(header.getKey(), header.getValue());
		});
		if (context.user() != null) {
			req.putHeader("user-principal", context.user().principal().encode());
		}
		// send request
		if (context.getBody() == null) {
			req.end();
		} else {
			req.end(context.getBody());
		}

	}

	private void notFound(RoutingContext context, String message) {
		context.response().setStatusCode(404).putHeader("content-type", "application/json")
				.end(new JsonObject().put("message", message).encodePrettily());
	}

}
