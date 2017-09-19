package com.example.namesservice;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.collections4.CollectionUtils;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.servicediscovery.ServiceDiscovery;
import io.vertx.servicediscovery.types.HttpEndpoint;

public class NamesServiceVerticle extends AbstractVerticle {

	protected ServiceDiscovery discovery;
	/*
	 * Mientras este verticle solo se ejecute en una instancia de 
	 * event loop, esta forma de "persistencia" "funcionará"
	 */
	private static List<JsonObject> users;

	@Override
	public void start() throws Exception {
		discovery = ServiceDiscovery.create(vertx);

		Router router = Router.router(vertx);
		router.route().handler(BodyHandler.create());
		router.route(HttpMethod.GET, "/").handler(this::dispatchGetUsers);
		router.route(HttpMethod.POST,"/").handler(this::dispatchPostUser);
		
		vertx.createHttpServer().requestHandler(router::accept).listen(8080, ar -> {
			if (ar.succeeded()) {
				System.out.println("Server names started");
				discovery.publish(HttpEndpoint.createRecord("namesservice", "localhost", 8080, "/", new JsonObject().put("api.name", "names")),er->{
					if(!er.succeeded()){
						System.out.println(er.cause());
					}
				});
			} else {
				System.out.println("Cannot start names the server: " + ar.cause());
			}
		});

	}
	
	private void dispatchGetUsers(RoutingContext rc){
		rc.request().response().putHeader("content-type", "application/json")
		.end(Json.encodePrettily(CollectionUtils.emptyIfNull(users)));
	}
	
	private void dispatchPostUser(RoutingContext rc){
		JsonObject body = rc.getBodyAsJson();
		addUser(body);
		rc.request().response()
		.putHeader("content-type", "application/json")
		.setStatusCode(200)
		.end(Json.encodePrettily(body));
	}

	private void addUser(JsonObject body) {
		if(users==null){
			users = new ArrayList<>();
		}
		users.add(body);
	}

}
