package io.keen.client.android;

import android.content.Context;
import com.fasterxml.jackson.core.type.TypeReference;
import io.keen.client.android.exceptions.KeenException;
import io.keen.client.android.exceptions.NoWriteKeyException;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Matchers;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.*;

import static junit.framework.Assert.*;
import static junit.framework.Assert.assertEquals;
import static org.mockito.Mockito.*;

/**
 * KeenClientTest
 *
 * @author dkador
 * @since 1.0.0
 */
public class KeenClientTest {

    @BeforeClass
    public static void classSetUp() {
        KeenLogging.enableLogging();
    }

    @Before
    public void testSetUp() {
        TestUtils.deleteRecursively(getKeenClientCacheDir());
    }

    @Test
    public void testKeenClientConstructor() {
        Context context = getMockedContext();

        runKeenClientConstructorTest(null, null, null, null, true, "null context",
                                     "Android Context cannot be null.");
        runKeenClientConstructorTest(context, null, null, null, true, "null project id",
                                     "Invalid project ID specified: null");
        runKeenClientConstructorTest(context, "", null, null, true, "empty project id",
                                     "Invalid project ID specified: ");
        runKeenClientConstructorTest(context, "abc", null, null, false, "everything is good",
                                     null);
        runKeenClientConstructorTest(context, "abc", "def", "ghi", false, "keys",
                                     null);
    }

    private void runKeenClientConstructorTest(Context context, String projectId,
                                              String writeKey, String readKey,
                                              boolean shouldFail, String msg,
                                              String expectedMessage) {
        try {
            KeenClient client = new KeenClient(context, projectId, writeKey, readKey);
            if (shouldFail) {
                fail(msg);
            } else {
                doClientAssertions(context, projectId, writeKey, readKey, client);
            }
        } catch (IllegalArgumentException e) {
            assertEquals(expectedMessage, e.getLocalizedMessage());
        }
    }

    @Test
    public void testSharedClient() {
        // can't get client without first initializing it
        try {
            KeenClient.client();
            fail("can't get client without first initializing it");
        } catch (IllegalStateException e) {
        }

        // make sure bad values error correctly
        try {
            KeenClient.initialize(null, null, null, null);
            fail("can't use bad values");
        } catch (IllegalArgumentException e) {
        }

        Context context = getMockedContext();
        KeenClient.initialize(context, "abc", "def", "ghi");
        KeenClient client = KeenClient.client();
        doClientAssertions(context, "abc", "def", "ghi", client);
    }

    @Test
    public void testInvalidEventCollection() throws KeenException {
        runAddEventTestFail(TestUtils.getSimpleEvent(), "$asd", "collection can't start with $",
                            "An event collection name cannot start with the dollar sign ($) character.");

        String tooLong = TestUtils.getString(257);
        runAddEventTestFail(TestUtils.getSimpleEvent(), tooLong, "collection can't be longer than 256 chars",
                            "An event collection name cannot be longer than 256 characters.");
    }

    @Test
    public void testAddEventNoWriteKey() throws KeenException, IOException {
        KeenClient client = getClient("508339b0897a2c4282000000", null, null);
        Map<String, Object> event = new HashMap<String, Object>();
        event.put("test key", "test value");
        try {
            client.addEvent("foo", event);
            fail("add event without write key should fail");
        } catch (NoWriteKeyException e) {
            assertEquals("You can't send events to Keen IO if you haven't set a write key.",
                         e.getLocalizedMessage());
        }
    }

    @Test
    public void testAddEvent() throws KeenException, IOException {
        runAddEventTestFail(null, "foo", "null event",
                            "You must specify a non-null, non-empty event.");

        runAddEventTestFail(new HashMap<String, Object>(), "foo", "empty event",
                            "You must specify a non-null, non-empty event.");

        Map<String, Object> event = new HashMap<String, Object>();
        event.put("keen", "reserved");
        runAddEventTestFail(event, "foo", "keen reserved",
                            "An event cannot contain a root-level property named 'keen'.");

        event.remove("keen");
        event.put("ab.cd", "whatever");
        runAddEventTestFail(event, "foo", ". in property name",
                            "An event cannot contain a property with the period (.) character in it.");

        event.remove("ab.cd");
        event.put("$a", "whatever");
        runAddEventTestFail(event, "foo", "$ at start of property name",
                            "An event cannot contain a property that starts with the dollar sign ($) character in it.");

        event.remove("$a");
        String tooLong = TestUtils.getString(257);
        event.put(tooLong, "whatever");
        runAddEventTestFail(event, "foo", "too long property name",
                            "An event cannot contain a property name longer than 256 characters.");

        event.remove(tooLong);
        tooLong = TestUtils.getString(10000);
        event.put("long", tooLong);
        runAddEventTestFail(event, "foo", "too long property value",
                            "An event cannot contain a string property value longer than 10,000 characters.");

        // now do a basic add
        event.remove("long");
        event.put("valid key", "valid value");
        KeenClient client = getClient();
        client.addEvent("foo", event);
        // make sure the event's there
        Map<String, Object> storedEvent = getFirstEventForCollection(client, "foo");
        assertNotNull(storedEvent);
        assertEquals("valid value", storedEvent.get("valid key"));
        // also make sure the event has been timestamped
        @SuppressWarnings("unchecked")
        Map<String, Object> keenNamespace = (Map<String, Object>) storedEvent.get("keen");
        assertNotNull(keenNamespace);
        assertNotNull(keenNamespace.get("timestamp"));

        // an event with a Calendar should work
        Calendar now = Calendar.getInstance();
        event.put("datetime", now);
        client.addEvent("foo", event);
        File[] files = client.getFilesInDir(client.getEventDirectoryForEventCollection("foo"));
        assertEquals(2, files.length);

        // an event with a nested property called "keen" should work
        event = TestUtils.getSimpleEvent();
        Map<String, Object> nested = new HashMap<String, Object>();
        nested.put("keen", "value");
        event.put("nested", nested);
        client.addEvent("foo", event);
        files = client.getFilesInDir(client.getEventDirectoryForEventCollection("foo"));
        assertEquals(3, files.length);
    }

    @Test
    public void testUploadSuccess() throws KeenException, IOException {
        // this is the only test that does a full round-trip to the real API. the others mock out the connection.
        KeenClient client = getClient();
        Map<String, Object> event = new HashMap<String, Object>();
        event.put("test key", "test value");
        client.addEvent("foo", event);
        client.upload(null);
        // make sure file was deleted
        assertNull(getFirstEventForCollection(client, "foo"));
    }

    @Test
    public void testUploadFailedServerDown() throws Exception {
        KeenClient client = getMockedClient("", 500);
        addSimpleEventAndUpload(client);
        // make sure the file wasn't deleted locally
        assertNotNull(getFirstEventForCollection(client, "foo"));
    }

    @Test
    public void testUploadFailedServerDownNonJsonResponse() throws Exception {
        KeenClient client = getMockedClient("bad data", 500);
        addSimpleEventAndUpload(client);
        // make sure the file wasn't deleted locally
        assertNotNull(getFirstEventForCollection(client, "foo"));
    }

    @Test
    public void testUploadFailedBadRequest() throws Exception {
        Object response = buildResponseJson(false, "InvalidCollectionNameError", "anything");
        KeenClient client = getMockedClient(response, 200);
        addSimpleEventAndUpload(client);
        // make sure the file was deleted locally
        assertNull(getFirstEventForCollection(client, "foo"));
    }

    @Test
    public void testUploadFailedBadRequestUnknownError() throws Exception {
        KeenClient client = getMockedClient("doesn't matter", 400);
        addSimpleEventAndUpload(client);
        // make sure the file wasn't deleted locally
        assertNotNull(getFirstEventForCollection(client, "foo"));
    }

    @Test
    public void testUploadMultipleEventsSameCollectionSuccess() throws Exception {
        Object individualResult = buildResult(true, null, null);
        List<Object> list = new ArrayList<Object>();
        // add it twice
        list.add(individualResult);
        list.add(individualResult);
        Map<String, Object> result = new HashMap<String, Object>();
        String eventCollection = "foo";
        result.put(eventCollection, list);

        Map<String, Object> event = TestUtils.getSimpleEvent();

        KeenClient client = getMockedClient(result, 200);
        client.addEvent(eventCollection, event);
        client.addEvent(eventCollection, event);

        client.upload(null);

        assertNull(getFirstEventForCollection(client, eventCollection));
    }

    @Test
    public void testUploadMultipleEventsDifferentCollectionsSuccess() throws Exception {
        Object individualResult = buildResult(true, null, null);
        List<Object> list1 = new ArrayList<Object>();
        List<Object> list2 = new ArrayList<Object>();
        list1.add(individualResult);
        list2.add(individualResult);
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("foo", list1);
        result.put("bar", list2);

        Map<String, Object> event = TestUtils.getSimpleEvent();

        KeenClient client = getMockedClient(result, 200);
        client.addEvent("foo", event);
        client.addEvent("bar", event);

        client.upload(null);

        assertNull(getFirstEventForCollection(client, "foo"));
        assertNull(getFirstEventForCollection(client, "bar"));
    }

    @Test
    public void testUploadMultipleEventsSameCollectionOneFails() throws Exception {
        Object result1 = buildResult(true, null, null);
        Object result2 = buildResult(false, "InvalidCollectionNameError", "anything");
        List<Object> list1 = new ArrayList<Object>();
        list1.add(result1);
        list1.add(result2);
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("foo", list1);

        Map<String, Object> event = TestUtils.getSimpleEvent();

        KeenClient client = getMockedClient(result, 200);
        client.addEvent("foo", event);
        client.addEvent("foo", event);

        client.upload(null);

        assertNull(getFirstEventForCollection(client, "foo"));
    }

    @Test
    public void testUploadMultipleEventsDifferentCollectionsOneFails() throws Exception {
        Object result1 = buildResult(true, null, null);
        Object result2 = buildResult(false, "InvalidCollectionNameError", "anything");
        List<Object> list1 = new ArrayList<Object>();
        List<Object> list2 = new ArrayList<Object>();
        list1.add(result1);
        list2.add(result2);
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("foo", list1);
        result.put("bar", list2);

        Map<String, Object> event = TestUtils.getSimpleEvent();

        KeenClient client = getMockedClient(result, 200);
        client.addEvent("foo", event);
        client.addEvent("bar", event);

        client.upload(null);

        assertNull(getFirstEventForCollection(client, "foo"));
        assertNull(getFirstEventForCollection(client, "bar"));
    }

    @Test
    public void testUploadMultipleEventsDifferentCollectionsOneFailsForServerReason() throws Exception {
        Object result1 = buildResult(true, null, null);
        Object result2 = buildResult(false, "barf", "anything");
        List<Object> list1 = new ArrayList<Object>();
        List<Object> list2 = new ArrayList<Object>();
        list1.add(result1);
        list2.add(result2);
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("foo", list1);
        result.put("bar", list2);

        Map<String, Object> event = TestUtils.getSimpleEvent();

        KeenClient client = getMockedClient(result, 200);
        client.addEvent("foo", event);
        client.addEvent("bar", event);

        client.upload(null);

        assertNull(getFirstEventForCollection(client, "foo"));
        assertNotNull(getFirstEventForCollection(client, "bar"));
    }

    @Test
    public void testTooManyEventsCached() throws Exception {
        KeenClient client = getClient();
        Map<String, Object> event = TestUtils.getSimpleEvent();
        // create 5 events
        for (int i = 0; i < 5; i++) {
            client.addEvent("foo", event);
        }

        // should be 5 events now
        File[] files = client.getFilesForEventCollection("foo");
        assertEquals(5, files.length);
        // now do 1 more, should age out 2 old ones
        client.addEvent("foo", event);
        // so now there should be 4 left (5 - 2 + 1)
        assertEquals(4, client.getFilesForEventCollection("foo").length);
    }

    @Test
    public void testGlobalPropertiesMap() throws Exception {
        // a null map should be okay
        runGlobalPropertiesMapTest(null, 1);

        // an empty map should be okay
        runGlobalPropertiesMapTest(new HashMap<String, Object>(), 1);

        // a map w/ non-conflicting property names should be okay
        Map<String, Object> globals = new HashMap<String, Object>();
        globals.put("default name", "default value");
        String eventCollection = runGlobalPropertiesMapTest(globals, 2);
        assertEquals("default value", getFirstEventForCollection(getClient(), eventCollection).get("default name"));

        // a map that returns a conflicting property name should not overwrite the property on the event
        globals = new HashMap<String, Object>();
        globals.put("a", "c");
        eventCollection = runGlobalPropertiesMapTest(globals, 1);
        assertEquals("b", getFirstEventForCollection(getClient(), eventCollection).get("a"));
    }

    private String runGlobalPropertiesMapTest(Map<String, Object> globalProperties,
                                              int expectedNumProperties) throws Exception {
        KeenClient client = getClient();
        client.setGlobalProperties(globalProperties);
        Map<String, Object> event = TestUtils.getSimpleEvent();
        String eventCollection = String.format("foo%d", Calendar.getInstance().getTimeInMillis());
        client.addEvent(eventCollection, event);
        assertEquals(expectedNumProperties + 1, getFirstEventForCollection(client, eventCollection).size());
        return eventCollection;
    }

    @Test
    public void testGlobalPropertiesEvaluator() throws Exception {
        // a null evaluator should be okay
        runGlobalPropertiesEvaluatorTest(null, 1);

        // an evaluator that returns an empty map should be okay
        GlobalPropertiesEvaluator evaluator = new GlobalPropertiesEvaluator() {
            @Override
            public Map<String, Object> getGlobalProperties(String eventCollection) {
                return new HashMap<String, Object>();
            }
        };
        runGlobalPropertiesEvaluatorTest(evaluator, 1);

        // an evaluator that returns a map w/ non-conflicting property names should be okay
        evaluator = new GlobalPropertiesEvaluator() {
            @Override
            public Map<String, Object> getGlobalProperties(String eventCollection) {
                Map<String, Object> globals = new HashMap<String, Object>();
                globals.put("default name", "default value");
                return globals;
            }
        };
        String eventCollection = runGlobalPropertiesEvaluatorTest(evaluator, 2);
        assertEquals("default value", getFirstEventForCollection(getClient(), eventCollection).get("default name"));

        // an evaluator that returns a map w/ conflicting property name should not overwrite the property on the event
        evaluator = new GlobalPropertiesEvaluator() {
            @Override
            public Map<String, Object> getGlobalProperties(String eventCollection) {
                Map<String, Object> globals = new HashMap<String, Object>();
                globals.put("a", "c");
                return globals;
            }
        };
        eventCollection = runGlobalPropertiesEvaluatorTest(evaluator, 1);
        assertEquals("b", getFirstEventForCollection(getClient(), eventCollection).get("a"));
    }

    private String runGlobalPropertiesEvaluatorTest(GlobalPropertiesEvaluator evaluator,
                                                    int expectedNumProperties) throws Exception {
        KeenClient client = getClient();
        client.setGlobalPropertiesEvaluator(evaluator);
        Map<String, Object> event = TestUtils.getSimpleEvent();
        String eventCollection = String.format("foo%d", Calendar.getInstance().getTimeInMillis());
        client.addEvent(eventCollection, event);
        assertEquals(expectedNumProperties + 1, getFirstEventForCollection(client, eventCollection).size());
        return eventCollection;
    }

    @Test
    public void testGlobalPropertiesTogether() throws Exception {
        KeenClient client = getClient();

        // properties from the evaluator should take precedence over properties from the map
        // but properties from the event itself should take precedence over all
        Map<String, Object> globalProperties = new HashMap<String, Object>();
        globalProperties.put("default property", 5);
        globalProperties.put("foo", "some new value");

        GlobalPropertiesEvaluator evaluator = new GlobalPropertiesEvaluator() {
            @Override
            public Map<String, Object> getGlobalProperties(String eventCollection) {
                Map<String, Object> map = new HashMap<String, Object>();
                map.put("default property", 6);
                map.put("foo", "some other value");
                return map;
            }
        };

        client.setGlobalProperties(globalProperties);
        client.setGlobalPropertiesEvaluator(evaluator);

        Map<String, Object> event = new HashMap<String, Object>();
        event.put("foo", "bar");
        client.addEvent("apples", event);

        Map<String, Object> storedEvent = getFirstEventForCollection(client, "apples");
        assertEquals("bar", storedEvent.get("foo"));
        assertEquals(6, storedEvent.get("default property"));
        assertEquals(3, storedEvent.size());
    }

    @Test
    public void testGetKeenCacheDirectory() throws Exception {
        File dir = new File(getMockedContext().getCacheDir(), "keen");
        if (dir.exists()) {
            assert dir.delete();
        }

        KeenClient client = getClient();
        Map<String, Object> event = new HashMap<String, Object>();
        event.put("a", "apple");
        client.addEvent("foo", event);
    }

    private void addSimpleEventAndUpload(KeenClient mockedClient) throws KeenException {
        Map<String, Object> event = new HashMap<String, Object>();
        event.put("a", "apple");
        mockedClient.addEvent("foo", event);
        mockedClient.upload(null);
    }

    private Map<String, Object> buildResult(boolean success, String errorCode, String description) {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("success", success);
        if (!success) {
            Map<String, Object> error = new HashMap<String, Object>();
            error.put("name", errorCode);
            error.put("description", description);
            result.put("error", error);
        }
        return result;
    }

    private Map<String, Object> buildResponseJson(boolean success, String errorCode, String description) {
        Map<String, Object> result = buildResult(success, errorCode, description);
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        list.add(result);
        Map<String, Object> response = new HashMap<String, Object>();
        response.put("foo", list);
        return response;
    }

    private KeenClient getMockedClient(Object data, int statusCode) throws IOException {
        if (data == null) {
            data = buildResponseJson(true, null, null);
        }

        // set up the partial mock
        KeenClient client = getClient();
        client = spy(client);

        byte[] bytes = KeenClient.MAPPER.writeValueAsBytes(data);
        ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
        HttpURLConnection connMock = mock(HttpURLConnection.class);
        when(connMock.getResponseCode()).thenReturn(statusCode);
        if (statusCode == 200) {
            when(connMock.getInputStream()).thenReturn(stream);
        } else {
            when(connMock.getErrorStream()).thenReturn(stream);
        }

        doReturn(connMock).when(client).sendEvents(Matchers.<Map<String, List<Map<String, Object>>>>any());

        return client;
    }

    private Map<String, Object> getFirstEventForCollection(KeenClient client,
                                                           String eventCollection) throws IOException {
        File dir = client.getEventDirectoryForEventCollection(eventCollection);
        File[] files = client.getFilesInDir(dir);
        if (files.length == 0) {
            return null;
        } else {
            return KeenClient.MAPPER.readValue(files[0], new TypeReference<Map<String, Object>>() {
            });
        }
    }

    private void runAddEventTestFail(Map<String, Object> event, String eventCollection, String msg,
                                     String expectedMessage) {
        KeenClient client = getClient();
        try {
            client.addEvent(eventCollection, event);
            fail(msg);
        } catch (KeenException e) {
            assertEquals(expectedMessage, e.getLocalizedMessage());
        }
    }

    private void doClientAssertions(Context expectedContext, String expectedProjectId,
                                    String expectedWriteKey, String expectedReadKey, KeenClient client) {
        assertEquals(expectedContext, client.getContext());
        assertEquals(expectedProjectId, client.getProjectId());
        assertEquals(expectedWriteKey, client.getWriteKey());
        assertEquals(expectedReadKey, client.getReadKey());
    }

    private KeenClient getClient() {
        return getClient("508339b0897a2c4282000000", "80ce00d60d6443118017340c42d1cfaf",
                         "80ce00d60d6443118017340c42d1cfaf");
    }

    private KeenClient getClient(String projectId, String writeKey, String readKey) {
        KeenClient client = new KeenClient(getMockedContext(), projectId, writeKey, readKey);
        client.setIsRunningTests(true);
        return client;
    }

    private Context getMockedContext() {
        Context mockContext = mock(Context.class);
        when(mockContext.getCacheDir()).thenReturn(getCacheDir());
        return mockContext;
    }

    private static File getCacheDir() {
        return new File("/tmp");
    }

    private static File getKeenClientCacheDir() {
        return new File(getCacheDir(), "keen");
    }

}
