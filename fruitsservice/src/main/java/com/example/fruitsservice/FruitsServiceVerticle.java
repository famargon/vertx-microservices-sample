package com.example.fruitsservice;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.servicediscovery.ServiceDiscovery;
import io.vertx.servicediscovery.types.HttpEndpoint;

public class FruitsServiceVerticle extends AbstractVerticle {

	protected ServiceDiscovery discovery;
	private static List<String> fruits;
	
	@Override
	public void start() throws Exception {
		discovery = ServiceDiscovery.create(vertx);

		Router router = Router.router(vertx);
		router.route(HttpMethod.GET, "/").handler(this::dispatchGetList);
		router.route(HttpMethod.GET, "/add/:fruitname").handler(this::dispatchAdd);

		initHttpServer(router);

	}

	private void dispatchGetList(RoutingContext rc) {
		rc.request()
		.response()
		.putHeader("content-type", "application/json")
		.end(Json.encodePrettily(
				getFruitsList().stream()
				.map(value -> new JsonObject().put("name", value))
				.collect(Collectors.toList())));
	}
	
	private void dispatchAdd(RoutingContext context){
		HttpServerRequest request = context.request();
		String name = request.getParam("fruitname");
		if(StringUtils.isBlank(name) || getFruitsList().contains(name)){
			request.response().setStatusCode(400).end("Not allowed");
			return;
		}
		addFruit(name);
		dispatchGetList(context);
	}

	private void initHttpServer(Router router) {
		vertx.createHttpServer().requestHandler(router::accept).listen(8090, ar -> {
			if (ar.succeeded()) {
				System.out.println("Server fruits started");
				discovery.publish(HttpEndpoint.createRecord("fruitsservice", "localhost", 8090, "/", new JsonObject().put("api.name", "fruits")), er -> {
					if(!er.succeeded()){
						System.out.println(er.cause());
					}
				});
			} else {
				System.out.println("Cannot start fruits the server: " + ar.cause());
			}
		});
	}
	
	private void addFruit(String fruit){
		getFruitsList().add(fruit);
	}
	
	private List<String> getFruitsList() {
		if(fruits==null){
			fruits = newDefaultFruitsList();
		}
		return fruits;
	}

	private List<String> newDefaultFruitsList() {
		List<String> fruits = new ArrayList<>();
		fruits.add("orange");
		return fruits;
	}
}