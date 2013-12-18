Keen Android Client
===================

Use this library from any Android application where you want to record data using Keen IO.

[API Documentation](https://keen.io/docs/clients/android/usage-guide/)

[Client Documentation](https://keen.io/static/android-reference/index.html)

### Installation

Just drop the jar we've created into your project and configure the project to use it. We recommend having a "libs" directory to contain external dependencies, but it's up to you.

Download the jar [here](http://keen.io/static/code/KeenClient-Android.jar).

##### Required Permissions

If it’s not already present, add the INTERNET permission to your AndroidManifest.xml file. The entry below should appear between the <manifest> .. </manifest> tags.

    <uses-permission android:name="android.permission.INTERNET"/>

### Usage

To use this client with the Keen IO API, you have to configure your Keen IO Project ID and its access keys (if you need an account, [sign up here](https://keen.io/) - it's free).

##### Register Your Project ID and Access Keys

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        // initialize the Keen Client with your Project Token.
        KeenClient.initialize(getApplicationContext(), KEEN_PROJECT_TOKEN, KEEN_WRITE_KEY, KEEN_READ_KEY);
    }

The write key is required to send events to Keen IO - the read key is required to do analysis on Keen IO.

##### Add Events

Here’s a very basic example for an app that tracks "purchases" whenever the app is resumed:

    @Override
    protected void onResume() {
        super.onResume();

        // create an event to eventually upload to Keen
        Map<String, Object> event = new HashMap<String, Object>();
        event.put("item", "golden widget");

        // add it to the "purchases" collection in your Keen Project
        try {
            KeenClient.client().addEvent("purchases", event);
        } catch (KeenException e) {
            // handle the exception in a way that makes sense to you
            e.printStackTrace();
        }
    }

##### Upload Events to Keen IO

Adding events just stores the events locally on the device. You must explicitly upload them to Keen IO. Here's an example:

    @Override
    protected void onPause() {
        // upload all captured events to Keen
        KeenClient.client().upload(new UploadFinishedCallback() {
            public void callback() {
                // use this to notify yourself when the upload finishes, if you wish. we'll just log for now.
                Log.i("KeenAndroidSample", "Keen client has finished uploading!");
            }
        });

        super.onPause();
    }

##### Do analysis with Keen IO

    TODO

That's it! After running your code, check your Keen IO Project to see the event has been added.

### Changelog

##### 1.0.3

+ Fix a number of potential issues related to the keen cache directory being in weird states.

##### 1.0.2

+ If keen cache directory doesn't exist, create it.

##### 1.0.1

+ Changed project token -> project ID.
+ Added support for read and write scoped keys.

### To Do

* Support analysis APIs.
* Native Android visualizations.

### Questions & Support

If you have any questions, bugs, or suggestions, please
report them via Github Issues. Or, come chat with us anytime
at [users.keen.io](http://users.keen.io). We'd love to hear your feedback and ideas!

### Contributing
This is an open source project and we love involvement from the community! Hit us up with pull requests and issues.
