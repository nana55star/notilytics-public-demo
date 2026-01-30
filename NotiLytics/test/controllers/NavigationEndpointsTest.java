package controllers;

import org.junit.jupiter.api.Test;
import play.Application;
import play.inject.guice.GuiceApplicationBuilder;
import play.mvc.Http;
import play.mvc.Result;
import play.test.Helpers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static play.mvc.Http.Status.OK;
import static play.test.Helpers.*;

/**
 * Navigation smoke tests that ensure the combined {@link SearchController}
 * renders the expected pages.
 */
public class NavigationEndpointsTest {

    private Application app() {
        return new GuiceApplicationBuilder().build();
    }

    @Test
    void source_page_ok() {
        Application app = app();
        Helpers.running(app, () -> {
            Result r = route(app, fakeRequest(GET, "/source"));
            assertEquals(OK, r.status());
        });
    }

    @Test
    void wordstats_page_ok() {
        Application app = app();
        Helpers.running(app, () -> {
            Result r = route(app, fakeRequest(GET, "/wordstats"));
            assertEquals(OK, r.status());
        });
    }

    @Test
    void index_method_ok() {
        Application app = app();
        Helpers.running(app, () -> {
            SearchController controller = app.injector().instanceOf(SearchController.class);
            Http.Request req = Helpers.fakeRequest().build();
            Result res = controller.index(req);
            assertEquals(OK, res.status());
        });
    }

    @Test
    void source_page_withNullSelectedId_defaultsToEmpty() {
        Application app = app();
        Helpers.running(app, () -> {
            Result r = route(app, fakeRequest(GET, "/source"));
            assertEquals(OK, r.status());
            String body = contentAsString(r);
            assertTrue(body.contains("data-selected-id=\"\""));
            assertTrue(body.contains("data-selected-name=\"\""));
        });
    }

    @Test
    void source_page_withNullSelectedName_defaultsToEmpty() {
        Application app = app();
        Helpers.running(app, () -> {
            Result r = route(app, fakeRequest(GET, "/source?sourceId=cnn"));
            assertEquals(OK, r.status());
            String body = contentAsString(r);
            assertTrue(body.contains("data-selected-id=\"cnn\""));
            assertTrue(body.contains("data-selected-name=\"\""));
        });
    }
}
