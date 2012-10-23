package io.keen.client.android;

import java.util.Scanner;

/**
 * KeenUtils
 *
 * @author dkador
 * @since 1.0.0
 */
class KeenUtils {

    static String convertStreamToString(java.io.InputStream is) {
        try {
            return new Scanner(is).useDelimiter("\\A").next();
        } catch (java.util.NoSuchElementException e) {
            return "";
        }
    }

}
