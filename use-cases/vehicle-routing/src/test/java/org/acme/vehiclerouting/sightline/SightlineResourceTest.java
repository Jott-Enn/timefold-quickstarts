package org.acme.vehiclerouting.sightline;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;

import java.util.Base64;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SightlineResourceTest {

    @Test
    void pageAndClientScriptAreServedInDevAndTest() {
        given().get("/sightline").then().statusCode(200).body(containsString("Findings"));
        given().get("/sightline/assets/client.js").then().statusCode(200).body(containsString("getDisplayMedia"));
        given().get("/sightline/assets/../application.properties").then().statusCode(404);
    }

    @Test
    void filesAFindingAndRefusesBadNames() throws Exception {
        ObjectNode sidecar = FindingStoreTest.sidecar("rest test", "page");
        String png = Base64.getEncoder().encodeToString(FindingStoreTest.png(40, 20));
        String name = given().contentType(ContentType.JSON).body(Map.of("png", png, "sidecar", sidecar))
                .post("/sightline/findings").then().statusCode(201)
                .body("name", matchesPattern("finding-\\d{8}-\\d{6}(-\\d+)?"))
                .extract().path("name");
        given().get("/sightline/findings/" + name + ".json").then().statusCode(200)
                .body("note", org.hamcrest.Matchers.equalTo("rest test"))
                .body("build.commit", org.hamcrest.Matchers.notNullValue())
                .body("backend.enabled", org.hamcrest.Matchers.equalTo(true));
        given().get("/sightline/findings/" + name + ".thumb.png").then().statusCode(200).contentType("image/png");
        given().get("/sightline/findings/pom.xml").then().statusCode(400);
        given().get("/sightline/findings/finding-20261009-130501-1.png").then().statusCode(400);
        given().get("/sightline/findings/finding-20261009-130501.png%2F..%2F..%2Fpom.xml").then().statusCode(400);
        given().contentType(ContentType.JSON).body(Map.of("verdict", "answered", "cause", "x"))
                .post("/sightline/findings/..%2Fx/close").then().statusCode(400);
        ObjectNode badSource = FindingStoreTest.sidecar("", "screen");
        given().contentType(ContentType.JSON).body(Map.of("png", png, "sidecar", badSource))
                .post("/sightline/findings").then().statusCode(400).body("error", containsString("Unknown source"));
        given().contentType(ContentType.JSON).body(Map.of("verdict", "answered", "cause", "looked at it, works as designed"))
                .post("/sightline/findings/" + name + "/close").then().statusCode(200);
        given().get("/sightline/findings").then().statusCode(200).body(containsString(name));
    }
}
