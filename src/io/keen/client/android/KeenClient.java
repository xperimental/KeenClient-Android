package io.keen.client.android;

import android.content.Context;
import android.os.AsyncTask;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.keen.client.android.exceptions.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

/**
 * KeenClient has static methods to return managed instances of itself and instance methods to collect new events
 * and upload them through the Keen API.
 * <p/>
 * Example usage:
 * <p/>
 * <pre>
 *     KeenClient.initialize(getApplicationContext(), "my_project_id",
 *                           "my_write_key", "my_read_key");
 *     Map<String, Object> myEvent = new HashMap<String, Object>();
 *     myEvent.put("property name", "property value");
 *     KeenClient.client().addEvent(myEvent, "purchases");
 *     KeenClient.client().upload(null);
 * </pre>
 *
 * @author dkador
 * @since 1.0.0
 */
public class KeenClient {

    static final ObjectMapper MAPPER;

    static {
        MAPPER = new ObjectMapper();
        MAPPER.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    private enum ClientSingleton {
        INSTANCE;
        private KeenClient client;
    }

    /**
     * Call this to initialize the singleton instance of KeenClient and set its Project ID and access keys.
     * <p/>
     * You'll generally want to call this at the very beginning of your application's lifecycle. Once you've called
     * this, you can then call KeenClient.client() afterwards.
     *
     * @param context   The Android context of your application.
     * @param projectId The Keen IO Project ID.
     * @param writeKey  A Keen IO Write Key.
     * @param readKey   A Keen IO Read Key.
     */
    public static void initialize(Context context, String projectId, String writeKey, String readKey)
            throws KeenInitializationException {
        ClientSingleton.INSTANCE.client = new KeenClient(context, projectId, writeKey, readKey);
    }

    /**
     * Call this to retrieve the singleton instance of KeenClient.
     * <p/>
     * If you only have to use a single Keen project, just use this.
     *
     * @return A managed instance of KeenClient, or null if KeenClient.initialize() hasn't been called previously.
     */
    public static KeenClient client() {
        if (ClientSingleton.INSTANCE.client == null) {
            throw new IllegalStateException("Please call KeenClient.initialize() before requesting the shared client.");
        }
        return ClientSingleton.INSTANCE.client;
    }

    /////////////////////////////////////////////

    private final Context context;
    private final String projectId;
    private final String writeKey;
    private final String readKey;
    private GlobalPropertiesEvaluator globalPropertiesEvaluator;
    private Map<String, Object> globalProperties;
    private boolean isRunningTests;
    private boolean active;

    /**
     * Call this if your code needs to use more than one Keen project (or if you don't want to use
     * the managed, singleton instance provided by this library).
     *
     * @param context   The Android context of your application.
     * @param projectId The Keen IO project ID.
     * @param writeKey  A Keen IO Write Key.
     * @param readKey   A Keen IO Read Key.
     */
    public KeenClient(Context context, String projectId, String writeKey, String readKey)
            throws KeenInitializationException {
        if (context == null) {
            throw new IllegalArgumentException("Android Context cannot be null.");
        }
        if (projectId == null || projectId.length() == 0) {
            throw new IllegalArgumentException("Invalid project ID specified: " + projectId);
        }

        this.context = context;
        this.projectId = projectId;
        this.writeKey = writeKey;
        this.readKey = readKey;
        this.globalPropertiesEvaluator = null;
        this.globalProperties = null;
        this.isRunningTests = false;
        this.active = true;
        KeenLogging.log("Keen using cache " + getKeenCacheDirectory().toString()); // this will init the cache if needed
        if (!this.isKeenCacheInitialized()) {
            this.setActive(false);
            throw new KeenInitializationException("Keen was unable to create a cache directory. Check logs for file " +
                    "permissions details. Keen has been disabled, recreate your Keen client to try cache creation again.");
        }
    }

    /////////////////////////////////////////////

    /**
     * Call this any time you want to add an event that will eventually be sent to the Keen IO server.
     * <p/>
     * The event will be stored on the local file system until you decide to upload (usually this will happen
     * right before your app goes into the background, but it could be any time).
     *
     * @param eventCollection The name of the event collection you want to put this event into.
     * @param event           A Map that consists of key/value pairs. Keen naming conventions apply (see docs).
     *                        Nested Maps and lists are acceptable (and encouraged!).
     * @throws KeenException
     */
    public void addEvent(String eventCollection, Map<String, Object> event) throws KeenException {
        if (this.isActive()) {
            addEvent(eventCollection, event, null);
        } else {
            KeenLogging.log(String.format("WARN Did not addEvent because KeenClient is not active. Event:\n %s, %s, %s",
                    eventCollection, event, null));
        }
    }

    /**
     * Call this any time you want to add an event that will eventually be sent to the Keen IO server AND
     * you want to override Keen-defaulted properties (like timestamp).
     * <p/>
     * The event will be stored on the local file system until you decide to upload (usually this will happen
     * right before your app goes into the background, but it could be any time).
     *
     * @param eventCollection The name of the event collection you want to put this event into.
     * @param event           A Map that consists of key/value pairs. Keen naming conventions apply (see docs).
     *                        Nested Maps and lists are acceptable (and encouraged!).
     * @param keenProperties  A Map that consists of key/value pairs to override default properties.
     *                        ex: "timestamp" -> Calendar.getInstance()
     * @throws KeenException
     */
    public void addEvent(String eventCollection, Map<String, Object> event, Map<String, Object> keenProperties)
            throws KeenException {
        if (this.isActive()) {
            if (getWriteKey() == null) {
                throw new NoWriteKeyException("You can't send events to Keen IO if you haven't set a write key.");
            }

            validateEventCollection(eventCollection);
            validateEvent(event);

            File dir = getEventDirectoryForEventCollection(eventCollection);
            // make sure it exists
            createDirIfItDoesNotExist(dir);
            // now make sure we haven't hit the max number of events in this collection already
            File[] files = getFilesInDir(dir);
            if (files.length >= getMaxEventsPerCollection()) {
                // need to age out old data so the cache doesn't grow too large
                KeenLogging.log(String.format("Too many events in cache for %s, aging out old data", eventCollection));
                KeenLogging.log(String.format("Count: %d and Max: %d", files.length, getMaxEventsPerCollection()));

                // delete the eldest (i.e. first we have to sort the list by name)
                List<File> fileList = Arrays.asList(files);
                Collections.sort(fileList, new Comparator<File>() {
                    @Override
                    public int compare(File file, File file1) {
                        return file.getAbsolutePath().compareToIgnoreCase(file1.getAbsolutePath());
                    }
                });
                for (int i = 0; i < getNumberEventsToForget(); i++) {
                    File f = fileList.get(i);
                    if (!f.delete()) {
                        KeenLogging.log(String.format("CRITICAL: can't delete file %s, cache is going to be too big",
                                f.getAbsolutePath()));
                    }
                }
            }

            KeenLogging.log(String.format("Adding event to collection: %s", eventCollection));

            // build the event
            Map<String, Object> newEvent = new HashMap<String, Object>();
            // handle keen properties
            Calendar timestamp = Calendar.getInstance();
            if (keenProperties == null) {
                keenProperties = new HashMap<String, Object>();
                keenProperties.put("timestamp", timestamp);
            } else {
                if (!keenProperties.containsKey("timestamp")) {
                    keenProperties.put("timestamp", timestamp);
                }
            }
            newEvent.put("keen", keenProperties);

            // handle global properties
            Map<String, Object> globalProperties = getGlobalProperties();
            if (globalProperties != null) {
                newEvent.putAll(globalProperties);
            }
            GlobalPropertiesEvaluator globalPropertiesEvaluator = getGlobalPropertiesEvaluator();
            if (globalPropertiesEvaluator != null) {
                Map<String, Object> props = globalPropertiesEvaluator.getGlobalProperties(eventCollection);
                if (props != null) {
                    newEvent.putAll(props);
                }
            }
            // now handle user-defined properties
            newEvent.putAll(event);

            File fileForEvent = getFileForEvent(eventCollection, timestamp);
            try {
                MAPPER.writeValue(fileForEvent, newEvent);
            } catch (IOException e) {
                KeenLogging.log(String.format("There was an error while JSON serializing an event to: %s",
                        fileForEvent.getAbsolutePath()));
                e.printStackTrace();
            }
        } else {
            KeenLogging.log(String.format("WARN Did not addEvent because KeenClient is not active/n %s, %s, %s",
                    eventCollection, event, keenProperties));
        }
    }

    private void validateEventCollection(String eventCollection) throws InvalidEventCollectionException {
        if (eventCollection == null || eventCollection.length() == 0) {
            throw new InvalidEventCollectionException("You must specify a non-null, " +
                    "non-empty event collection: " + eventCollection);
        }
        if (eventCollection.startsWith("$")) {
            throw new InvalidEventCollectionException("An event collection name cannot start with the dollar sign ($)" +
                    " character.");
        }
        if (eventCollection.length() > 256) {
            throw new InvalidEventCollectionException("An event collection name cannot be longer than 256 characters.");
        }
    }

    private void validateEvent(Map<String, Object> event) throws InvalidEventException {
        validateEvent(event, 0);
    }

    @SuppressWarnings("unchecked") // cast to generic Map will always be okay in this case
    private void validateEvent(Map<String, Object> event, int depth) throws InvalidEventException {
        if (depth == 0) {
            if (event == null || event.size() == 0) {
                throw new InvalidEventException("You must specify a non-null, non-empty event.");
            }
            if (event.containsKey("keen")) {
                throw new InvalidEventException("An event cannot contain a root-level property named 'keen'.");
            }
        }

        for (Map.Entry<String, Object> entry : event.entrySet()) {
            String key = entry.getKey();
            if (key.contains(".")) {
                throw new InvalidEventException("An event cannot contain a property with the period (.) character in " +
                        "it.");
            }
            if (key.startsWith("$")) {
                throw new InvalidEventException("An event cannot contain a property that starts with the dollar sign " +
                        "($) character in it.");
            }
            if (key.length() > 256) {
                throw new InvalidEventException("An event cannot contain a property name longer than 256 characters.");
            }
            Object value = entry.getValue();
            if (value instanceof String) {
                String strValue = (String) value;
                if (strValue.length() >= 10000) {
                    throw new InvalidEventException("An event cannot contain a string property value longer than 10," +
                            "000 characters.");
                }
            } else if (value instanceof Map) {
                validateEvent((Map<String, Object>) value, depth + 1);
            }
        }
    }

    /**
     * Call this whenever you want to upload all the events you've captured so far. This will spawn a background
     * thread and process all required HTTP requests.
     * <p/>
     * If an upload fails, the events will be saved for a later attempt.
     * <p/>
     * If a particular event is invalid, the event will be dropped from the queue and the failure message will
     * be logged.
     *
     * @param callback The callback to be executed once uploading is finished, regardless of whether or not the
     *                 upload succeeded.
     */
    public void upload(final UploadFinishedCallback callback) {
        if (this.isActive()) {
            if (isRunningTests) {
                // if we're running tests, run synchronously
                uploadHelper(callback);
            } else {
                // otherwise run asynchronously
                new UploadTask().execute(callback);
            }
        } else {
            KeenLogging.log("WARN Did not upload events because this KeenClient is not active");
        }
    }

    private class UploadTask extends AsyncTask<UploadFinishedCallback, Void, Void> {
        @Override
        protected Void doInBackground(UploadFinishedCallback... uploadFinishedCallbacks) {
            uploadHelper(uploadFinishedCallbacks[0]);
            return null;
        }
    }

    synchronized private void uploadHelper(UploadFinishedCallback callback) {
        // iterate through all the sub-directories containing events in the Keen cache
        File[] directories = getKeenCacheSubDirectories();

        if (directories != null) {

            // this map will hold the eventual API request we send off to the Keen API
            Map<String, List<Map<String, Object>>> requestMap = new HashMap<String, List<Map<String, Object>>>();
            // this map will hold references from a single directory to all its children
            Map<File, List<File>> fileMap = new HashMap<File, List<File>>();

            // iterate through the directories
            for (File directory : directories) {
                // get their files
                File[] files = getFilesInDir(directory);
                if (files != null) {

                    // build up the list of maps (i.e. events) based on those files
                    List<Map<String, Object>> requestList = new ArrayList<Map<String, Object>>();
                    // also remember what files we looked at
                    List<File> fileList = new ArrayList<File>();
                    for (File file : files) {
                        // iterate through the files, deserialize them from JSON, and then add them to the list
                        Map<String, Object> eventDict = readMapFromJsonFile(file);
                        requestList.add(eventDict);
                        fileList.add(file);
                    }
                    if (!requestList.isEmpty()) {
                        requestMap.put(directory.getName(), requestList);
                    }
                    fileMap.put(directory, fileList);

                } else {
                    KeenLogging.log("During upload the files list in the directory was null.");
                }
            }

            // START HTTP REQUEST, WRITE JSON TO REQUEST STREAM
            try {
                if (!fileMap.isEmpty() && !requestMap.isEmpty()) { // could be empty due to inner null check above on files
                    HttpURLConnection connection = sendEvents(requestMap);


                    if (connection.getResponseCode() == 200) {
                        InputStream input = connection.getInputStream();
                        // if the response was good, then handle it appropriately
                        Map<String, List<Map<String, Object>>> responseBody = MAPPER.readValue(input,
                                new TypeReference<Map<String,
                                        List<Map<String,
                                                Object>>>>() {
                                });
                        handleApiResponse(responseBody, fileMap);
                    } else {
                        // if the response was bad, make a note of it
                        KeenLogging.log(String.format("Response code was NOT 200. It was: %d", connection.getResponseCode()));
                        InputStream input = connection.getErrorStream();
                        String responseBody = KeenUtils.convertStreamToString(input);
                        KeenLogging.log(String.format("Response body was: %s", responseBody));
                    }

                } else {
                    KeenLogging.log("No API calls were made because there were no events to upload");
                }
            } catch (JsonMappingException jsonme) {
                KeenLogging.log(String.format("ERROR: There was a JsonMappingException while sending %s to the Keen API: \n %s",
                        requestMap.toString(), jsonme.toString()));
            } catch (IOException e) {
                KeenLogging.log("There was an IOException while sending events to the Keen API: \n" + e.toString());
            }
        } else {
            KeenLogging.log("During upload the directories list was null, indicating a bad pathname.");
        }

        if (callback != null) {
            callback.callback();
        }

    }

    HttpURLConnection sendEvents(Map<String, List<Map<String, Object>>> requestDict) throws IOException {
        // just using basic JDK HTTP library
        String urlString = String.format("%s/%s/projects/%s/events", KeenConstants.SERVER_ADDRESS,
                KeenConstants.API_VERSION, getProjectId());
        URL url = new URL(urlString);

        // set up the POST
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", getWriteKey());
        // we're writing
        connection.setDoOutput(true);
        OutputStream out = connection.getOutputStream();
        // write JSON to the output stream
        MAPPER.writeValue(out, requestDict);
        out.close();
        return connection;
    }

    void handleApiResponse(Map<String, List<Map<String, Object>>> responseBody,
                           Map<File, List<File>> fileDict) {
        for (Map.Entry<String, List<Map<String, Object>>> entry : responseBody.entrySet()) {
            // loop through all the event collections
            String collectionName = entry.getKey();
            List<Map<String, Object>> results = entry.getValue();
            int count = 0;
            for (Map<String, Object> result : results) {
                // now loop through each event collection's individual results
                boolean deleteFile = true;
                boolean success = (Boolean) result.get(KeenConstants.SUCCESS_PARAM);
                if (!success) {
                    // grab error code and description
                    Map errorDict = (Map) result.get(KeenConstants.ERROR_PARAM);
                    String errorCode = (String) errorDict.get(KeenConstants.NAME_PARAM);
                    if (errorCode.equals(KeenConstants.INVALID_COLLECTION_NAME_ERROR) ||
                            errorCode.equals(KeenConstants.INVALID_PROPERTY_NAME_ERROR) ||
                            errorCode.equals(KeenConstants.INVALID_PROPERTY_VALUE_ERROR)) {
                        deleteFile = true;
                        KeenLogging.log("An invalid event was found. Deleting it. Error: " +
                                errorDict.get(KeenConstants.DESCRIPTION_PARAM));
                    } else {
                        String description = (String) errorDict.get(KeenConstants.DESCRIPTION_PARAM);
                        deleteFile = false;
                        KeenLogging.log(String.format("The event could not be inserted for some reason. " +
                                "Error name and description: %s %s", errorCode,
                                description));
                    }
                }

                if (deleteFile) {
                    // we only delete the file if the upload succeeded or the error means we shouldn't retry the
                    // event later
                    File eventFile = fileDict.get(getEventDirectoryForEventCollection(collectionName)).get(count);
                    if (!eventFile.delete()) {
                        KeenLogging.log(String.format("CRITICAL ERROR: Could not remove event at %s",
                                eventFile.getAbsolutePath()));
                    } else {
                        KeenLogging.log(String.format("Successfully deleted file: %s", eventFile.getAbsolutePath()));
                    }
                }
                count++;
            }
        }
    }

    private Map<String, Object> readMapFromJsonFile(File jsonFile) {
        try {
            return MAPPER.readValue(jsonFile, new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException e) {
            KeenLogging.log(String.format(
                    "There was an error when attempting to deserialize the contents of %s into JSON.",
                    jsonFile.getAbsolutePath()));
            e.printStackTrace();
            return null;
        }
    }

    /////////////////////////////////////////////
    // FILE IO
    /////////////////////////////////////////////

    /**
     * Call this to verify the cache was properly initialized during the creation of the KeenClient.
     * <p/>
     * If the cache wasn't able to be initialized, the KeenClient will log the failure but not take down
     * your app with an unexpected Runtime Exception (due to the nature of Android's AsyncTask doPostExecute()).
     * <p/>
     * Check the logs to see if there are FATAL permissions issues with your cache directory.
     *
     */
    public boolean isKeenCacheInitialized() {
        File file = getKeenCacheDirectory();
        return file.exists();
    }

    File getDeviceCacheDirectory() {
        return getContext().getCacheDir();
    }

    private File getKeenCacheDirectory() {
        File file = new File(getDeviceCacheDirectory(), "keen");
        if (!file.exists()) {
            boolean dirMade = file.mkdir();
            if (!dirMade) {
                this.setActive(false);
                KeenLogging.log(String.format("FATAL ERROR: Could not make keen cache directory at: %s\n" +
                        "canRead: %s | canWrite: %s | canExecute: %s | isFile: %s \nKeen has been disabled", file.getAbsolutePath(),
                        file.canRead(), file.canWrite(), file.canExecute(), file.isFile()));
            } else {
                this.setActive(true);
            }
        }
        return file;
    }

    private File[] getKeenCacheSubDirectories() {
        return getKeenCacheDirectory().listFiles(new FileFilter() { // Can return null if there are no events
            public boolean accept(File file) {
                return file.isDirectory();
            }
        });
    }

    File[] getFilesInDir(File dir) {
        return dir.listFiles(new FileFilter() {
            public boolean accept(File file) {
                return file.isFile();
            }
        });
    }

    File getEventDirectoryForEventCollection(String eventCollection) {
        File file = new File(getKeenCacheDirectory(), eventCollection);
        if (!file.exists()) {
            KeenLogging.log("Cache directory for event collection '" + eventCollection + "' doesn't exist. " +
                    "Creating it.");
            if (!file.mkdirs()) {
                KeenLogging.log("Can't create dir: " + file.getAbsolutePath());
            }
        }
        return file;
    }

    File[] getFilesForEventCollection(String eventCollection) {
        return getFilesInDir(getEventDirectoryForEventCollection(eventCollection));
    }

    private File getFileForEvent(String eventCollection, Calendar timestamp) {
        File dir = getEventDirectoryForEventCollection(eventCollection);
        int counter = 0;
        File eventFile = getNextFileForEvent(dir, timestamp, counter);
        while (eventFile.exists()) {
            eventFile = getNextFileForEvent(dir, timestamp, counter);
            counter++;
        }
        return eventFile;
    }

    private File getNextFileForEvent(File dir, Calendar timestamp, int counter) {
        long timestampInMillis = timestamp.getTimeInMillis();
        String name = Long.toString(timestampInMillis);
        return new File(dir, name + "." + counter);
    }

    private void createDirIfItDoesNotExist(File dir) {
        if (!dir.exists()) {
            assert dir.mkdir();
        }
    }

    /////////////////////////////////////////////

    /**
     * Getter for the Android {@link Context} associated with this instance of the {@link KeenClient}.
     *
     * @return the Android {@link Context}
     */
    public Context getContext() {
        return context;
    }

    /**
     * Getter for the Keen Project ID associated with this instance of the {@link KeenClient}.
     *
     * @return the Keen Project ID
     */
    public String getProjectId() {
        return projectId;
    }

    /**
     * Getter for the Write Key associated with this instance of the {@link KeenClient}.
     *
     * @return the Write Key
     */
    public String getWriteKey() {
        return writeKey;
    }

    /**
     * Getter for the Read Key associated with this instance of the {@link KeenClient}.
     *
     * @return the Read Key
     */
    public String getReadKey() {
        return readKey;
    }

    /**
     * Getter for the Active Flag for this instance of the {@link KeenClient}.
     *
     * @return whether the client is active or not
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Setter for the Active Flag for this instance of the {@link KeenClient}. Deactivated when KeenClient has a problem
     * during initialization.
     */
    private void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Getter for the {@link GlobalPropertiesEvaluator} associated with this instance of the {@link KeenClient}.
     *
     * @return the {@link GlobalPropertiesEvaluator}
     */
    public GlobalPropertiesEvaluator getGlobalPropertiesEvaluator() {
        return globalPropertiesEvaluator;
    }

    /**
     * Call this to set the {@link GlobalPropertiesEvaluator} for this instance of the {@link KeenClient}.
     * The evaluator is invoked every time an event is added to an event collection.
     * <p/>
     * Global properties are properties which are sent with EVERY event. For example, you may wish to always
     * capture device information like OS version, handset type, orientation, etc.
     * <p/>
     * The evaluator takes as a parameter a single String, which is the name of the event collection the
     * event's being added to. You're responsible for returning a Map which represents the global properties
     * for this particular event collection.
     * <p/>
     * Note that because we use a class defined by you, you can create DYNAMIC global properties. For example,
     * if you want to capture device orientation, then your evaluator can ask the device for its current orientation
     * and then construct the Map. If your global properties aren't dynamic, then just return the same Map
     * every time.
     * <p/>
     * Example usage:
     * <pre>
     *     {@code KeenClient client = KeenClient.client();
     *     GlobalPropertiesEvaluator evaluator = new GlobalPropertiesEvaluator() {
     *         @Override
     *         public Map<String, Object> getGlobalProperties(String eventCollection) {
     *             Map<String, Object> map = new HashMap<String, Object>();
     *             map.put("some dynamic property name", "some dynamic property value");
     *             return map;
     *         }
     *     };
     *     client.setGlobalPropertiesEvaluator(evaluator);
     *     }
     * </pre>
     *
     * @param globalPropertiesEvaluator The evaluator which is invoked any time an event is added to an event
     *                                  collection.
     */
    public void setGlobalPropertiesEvaluator(GlobalPropertiesEvaluator globalPropertiesEvaluator) {
        this.globalPropertiesEvaluator = globalPropertiesEvaluator;
    }

    /**
     * Getter for the Keen Global Properties map. See docs for {@link #setGlobalProperties(java.util.Map)}.
     */
    public Map<String, Object> getGlobalProperties() {
        return globalProperties;
    }

    /**
     * Call this to set the Keen Global Properties Map for this instance of the {@link KeenClient}. The Map
     * is used every time an event is added to an event collection.
     * <p/>
     * Keen Global Properties are properties which are sent with EVERY event. For example, you may wish to always
     * capture static information like user ID, app version, etc.
     * <p/>
     * Every time an event is added to an event collection, the SDK will check to see if this property is defined.
     * If it is, the SDK will copy all the properties from the global properties into the newly added event.
     * <p/>
     * Note that because this is just a Map, it's much more difficult to create DYNAMIC global properties.
     * It also doesn't support per-collection properties. If either of these use cases are important to you, please use
     * the {@link GlobalPropertiesEvaluator}.
     * <p/>
     * Also note that the Keen properties defined in {@link #getGlobalPropertiesEvaluator()} take precedence over
     * the properties defined in getGlobalProperties, and that the Keen Properties defined in each
     * individual event take precedence over either of the Global Properties.
     * <p/>
     * Example usage:
     * <p/>
     * <pre>
     * KeenClient client = KeenClient.client();
     * Map<String, Object> map = new HashMap<String, Object>();
     * map.put("some standard key", "some standard value");
     * client.setGlobalProperties(map);
     * </pre>
     *
     * @param globalProperties The new map you wish to use as the Keen Global Properties.
     */
    public void setGlobalProperties(Map<String, Object> globalProperties) {
        this.globalProperties = globalProperties;
    }

    void setIsRunningTests(boolean isRunningTests) {
        this.isRunningTests = isRunningTests;
    }

    /////////////////////////////////////////////

    private int getMaxEventsPerCollection() {
        if (isRunningTests) {
            return 5;
        }
        return KeenConstants.MAX_EVENTS_PER_COLLECTION;
    }

    private int getNumberEventsToForget() {
        if (isRunningTests) {
            return 2;
        }
        return KeenConstants.NUMBER_EVENTS_TO_FORGET;
    }

}

