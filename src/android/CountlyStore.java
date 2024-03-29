/*
Copyright (c) 2012, 2013, 2014 Countly

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
*/
package ly.count.android.sdk;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * This class provides a persistence layer for the local event &amp; connection queues.
 *
 * The "read" methods in this class are not synchronized, because the underlying data store
 * provides thread-safe reads.  The "write" methods in this class are synchronized, because
 * 1) they often read a list of items, modify the list, and then commit it back to the underlying
 * data store, and 2) while the Countly singleton is synchronized to ensure only a single writer
 * at a time from the public API side, the internal implementation has a background thread that
 * submits data to a Countly server, and it writes to this store as well.
 *
 * NOTE: This class is only public to facilitate unit testing, because
 *       of this bug in dexmaker: https://code.google.com/p/dexmaker/issues/detail?id=34
 */
public class CountlyStore {
    private static final String PREFERENCES = "COUNTLY_STORE";
    private static final String PREFERENCES_GCM = "ly.count.android.api.messaging";
    private static final String DELIMITER = ":::";
    private static final String CONNECTIONS_PREFERENCE = "CONNECTIONS";
    private static final String EVENTS_PREFERENCE = "EVENTS";
    private static final String LOCATION_CITY_PREFERENCE = "LOCATION_CITY";
    private static final String LOCATION_COUNTRY_CODE_PREFERENCE = "LOCATION_COUNTRY_CODE";
    private static final String LOCATION_IP_ADDRESS_PREFERENCE = "LOCATION_IP_ADDRESS";
    private static final String LOCATION_PREFERENCE = "LOCATION";
    private static final String LOCATION_DISABLED_PREFERENCE = "LOCATION_DISABLED";
    private static final String STAR_RATING_PREFERENCE = "STAR_RATING";
    private static final String CACHED_ADVERTISING_ID = "ADVERTISING_ID";
    private static final String REMOTE_CONFIG_VALUES = "REMOTE_CONFIG";
    private static final int MAX_EVENTS = 100;
    private static final int MAX_REQUESTS = 1000;

    private final SharedPreferences preferences_;
    private final SharedPreferences preferencesGCM_;

    private static final String CONSENT_GCM_PREFERENCES = "ly.count.android.api.messaging.consent.gcm";

    /**
     * Constructs a CountlyStore object.
     * @param context used to retrieve storage meta data, must not be null.
     * @throws IllegalArgumentException if context is null
     */
    CountlyStore(final Context context) {
        if (context == null) {
            throw new IllegalArgumentException("must provide valid context");
        }
        preferences_ = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        preferencesGCM_ = context.getSharedPreferences(PREFERENCES_GCM, Context.MODE_PRIVATE);
    }

    /**
     * Returns an unsorted array of the current stored connections.
     */
    public String[] connections() {
        final String joinedConnStr = preferences_.getString(CONNECTIONS_PREFERENCE, "");
        return joinedConnStr.length() == 0 ? new String[0] : joinedConnStr.split(DELIMITER);
    }

    /**
     * Returns an unsorted array of the current stored event JSON strings.
     */
    public String[] events() {
        final String joinedEventsStr = preferences_.getString(EVENTS_PREFERENCE, "");
        return joinedEventsStr.length() == 0 ? new String[0] : joinedEventsStr.split(DELIMITER);
    }

    /**
     * Returns a list of the current stored events, sorted by timestamp from oldest to newest.
     */
    public List<Event> eventsList() {
        final String[] array = events();
        final List<Event> events = new ArrayList<>(array.length);
        for (String s : array) {
            try {
                final Event event = Event.fromJSON(new JSONObject(s));
                if (event != null) {
                    events.add(event);
                }
            } catch (JSONException ignored) {
                // should not happen since JSONObject is being constructed from previously stringified JSONObject
                // events -> json objects -> json strings -> storage -> json strings -> here
            }
        }
        // order the events from least to most recent
        Collections.sort(events, new Comparator<Event>() {
            @Override
            public int compare(final Event e1, final Event e2) {
                return (int)(e1.timestamp - e2.timestamp);
            }
        });
        return events;
    }

    /**
     * Returns true if no connections are current stored, false otherwise.
     */
    public boolean isEmptyConnections() {
        return preferences_.getString(CONNECTIONS_PREFERENCE, "").length() == 0;
    }

    /**
     * Adds a connection to the local store.
     * @param str the connection to be added, ignored if null or empty
     */
    public synchronized void addConnection(final String str) {
        if (str != null && str.length() > 0) {
            final List<String> connections = new ArrayList<>(Arrays.asList(connections()));
            if (connections.size() < MAX_REQUESTS) {
                connections.add(str);
                preferences_.edit().putString(CONNECTIONS_PREFERENCE, join(connections, DELIMITER)).apply();
            }
        }
    }

    /**
     * Removes a connection from the local store.
     * @param str the connection to be removed, ignored if null or empty,
     *            or if a matching connection cannot be found
     */
    public synchronized void removeConnection(final String str) {
        if (str != null && str.length() > 0) {
            final List<String> connections = new ArrayList<>(Arrays.asList(connections()));
            if (connections.remove(str)) {
                preferences_.edit().putString(CONNECTIONS_PREFERENCE, join(connections, DELIMITER)).apply();
            }
        }
    }

    /**
     * Adds a custom event to the local store.
     * @param event event to be added to the local store, must not be null
     */
    void addEvent(final Event event) {
        final List<Event> events = eventsList();
        if (events.size() < MAX_EVENTS) {
            events.add(event);
            preferences_.edit().putString(EVENTS_PREFERENCE, joinEvents(events, DELIMITER)).apply();
        }
    }

    /**
     * Sets location of user and sends it with next request
     */
    void setLocation(final String latLonCoordinates) {
        preferences_.edit().putString(LOCATION_PREFERENCE, latLonCoordinates).apply();
    }

    void setLocationCity(final String city) {
        preferences_.edit().putString(LOCATION_CITY_PREFERENCE, city).apply();
    }

    void setLocationCountryCode(final String countryCode) {
        preferences_.edit().putString(LOCATION_COUNTRY_CODE_PREFERENCE, countryCode).apply();
    }

    void setLocationIpAddress(final String ipAddress) {
        preferences_.edit().putString(LOCATION_IP_ADDRESS_PREFERENCE, ipAddress).apply();
    }

    void setLocationDisabled(final boolean locationDisabled) {
        preferences_.edit().putBoolean(LOCATION_DISABLED_PREFERENCE, locationDisabled).apply();
    }

    /**
     * Get location or empty string in case if no location is specified
     */
    String getLocation() {
        return preferences_.getString(LOCATION_PREFERENCE, "");
    }

    String getLocationCity() {
        return preferences_.getString(LOCATION_CITY_PREFERENCE, "");
    }

    String getLocationCountryCode() {
        return preferences_.getString(LOCATION_COUNTRY_CODE_PREFERENCE, "");
    }

    String getLocationIpAddress() {
        return preferences_.getString(LOCATION_IP_ADDRESS_PREFERENCE, "");
    }

    boolean getLocationDisabled() {
        return preferences_.getBoolean(LOCATION_DISABLED_PREFERENCE, false);
    }

    /**
     * Set the preferences that are used for the star rating
     */
    void setStarRatingPreferences(String prefs) {
        preferences_.edit().putString(STAR_RATING_PREFERENCE, prefs).apply();
    }

    /**
     * Get the preferences that are used for the star rating
     */
    String getStarRatingPreferences() {
        return preferences_.getString(STAR_RATING_PREFERENCE, "");
    }

    void setRemoteConfigValues(String values){
        preferences_.edit().putString(REMOTE_CONFIG_VALUES, values).apply();
    }

    String getRemoteConfigValues(){
        return preferences_.getString(REMOTE_CONFIG_VALUES, "");
    }


    void setCachedAdvertisingId(String advertisingId) {
        preferences_.edit().putString(CACHED_ADVERTISING_ID, advertisingId).apply();
    }

    String getCachedAdvertisingId() {
        return preferences_.getString(CACHED_ADVERTISING_ID, "");
    }

    void setConsentPush(boolean consentValue){
        preferencesGCM_.edit().putBoolean(CONSENT_GCM_PREFERENCES, consentValue).apply();
    }

    Boolean getConsentPush(){
        return preferencesGCM_.getBoolean(CONSENT_GCM_PREFERENCES, false);
    }

    /**
     * Adds a custom event to the local store.
     * @param key name of the custom event, required, must not be the empty string
     * @param segmentation segmentation values for the custom event, may be null
     * @param timestamp timestamp (seconds since 1970) in GMT when the event occurred
     * @param hour current local hour on device
     * @param dow current day of the week on device
     * @param count count associated with the custom event, should be more than zero
     * @param sum sum associated with the custom event, if not used, pass zero.
     *            NaN and infinity values will be quietly ignored.
     */
    public synchronized void addEvent(final String key, final Map<String, String> segmentation, final Map<String, Integer> segmentationInt, final Map<String, Double> segmentationDouble, final long timestamp, final int hour, final int dow, final int count, final double sum, final double dur) {
        final Event event = new Event();
        event.key = key;
        event.segmentation = segmentation;
        event.segmentationDouble = segmentationDouble;
        event.segmentationInt = segmentationInt;
        event.timestamp = timestamp;
        event.hour = hour;
        event.dow = dow;
        event.count = count;
        event.sum = sum;
        event.dur = dur;

        addEvent(event);
    }

    /**
     * Removes the specified events from the local store. Does nothing if the event collection
     * is null or empty.
     * @param eventsToRemove collection containing the events to remove from the local store
     */
    public synchronized void removeEvents(final Collection<Event> eventsToRemove) {
        if (eventsToRemove != null && eventsToRemove.size() > 0) {
            final List<Event> events = eventsList();
            if (events.removeAll(eventsToRemove)) {
                preferences_.edit().putString(EVENTS_PREFERENCE, joinEvents(events, DELIMITER)).apply();
            }
        }
    }

    /**
     * Converts a collection of Event objects to URL-encoded JSON to a string, with each
     * event JSON string delimited by the specified delimiter.
     * @param collection events to join into a delimited string
     * @param delimiter delimiter to use, should not be something that can be found in URL-encoded JSON string
     */
    @SuppressWarnings("SameParameterValue")
    static String joinEvents(final Collection<Event> collection, final String delimiter) {
        final List<String> strings = new ArrayList<>();
        for (Event e : collection) {
            strings.add(e.toJSON().toString());
        }
        return join(strings, delimiter);
    }

    /**
     * Joins all the strings in the specified collection into a single string with the specified delimiter.
     */
    static String join(final Collection<String> collection, final String delimiter) {
        final StringBuilder builder = new StringBuilder();

        int i = 0;
        for (String s : collection) {
            builder.append(s);
            if (++i < collection.size()) {
                builder.append(delimiter);
            }
        }

        return builder.toString();
    }

    /**
     * Retrieves a preference from local store.
     * @param key the preference key
     */
    public synchronized String getPreference(final String key) {
        return preferences_.getString(key, null);
    }

    /**
     * Adds a preference to local store.
     * @param key the preference key
     * @param value the preference value, supply null value to remove preference
     */
    public synchronized void setPreference(final String key, final String value) {
        if (value == null) {
            preferences_.edit().remove(key).apply();
        } else {
            preferences_.edit().putString(key, value).apply();
        }
    }

    // for unit testing
    synchronized void clear() {
        final SharedPreferences.Editor prefsEditor = preferences_.edit();
        prefsEditor.remove(EVENTS_PREFERENCE);
        prefsEditor.remove(CONNECTIONS_PREFERENCE);
        prefsEditor.clear();
        prefsEditor.apply();
    }
}
