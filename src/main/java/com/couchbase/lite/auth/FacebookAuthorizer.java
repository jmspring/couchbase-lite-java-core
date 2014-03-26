package com.couchbase.lite.auth;

import com.couchbase.lite.Database;
import com.couchbase.lite.support.HttpClientFactory;  //https://github.com/couchbase/couchbase-lite-java-core/issues/41
import com.couchbase.lite.util.Log;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Changed to implements from extends per https://github.com/couchbase/couchbase-lite-java-core/issues/41
public class FacebookAuthorizer implements Authorizer {

    public static final String LOGIN_PARAMETER_ACCESS_TOKEN = "access_token";
    public static final String QUERY_PARAMETER = "facebookAccessToken";
    public static final String QUERY_PARAMETER_EMAIL = "email";

    private static Map<List<String>, String> accessTokens;

    private String emailAddress;

    public FacebookAuthorizer(String emailAddress) {
        this.emailAddress = emailAddress;
    }

    public boolean usesCookieBasedLogin() {
        return true;
    }

    public Map<String, String> loginParametersForSite(URL site) {
        Map<String, String> loginParameters = new HashMap<String, String>();
        try {
            String accessToken = accessTokenForEmailAndSite(this.emailAddress, site);
            if (accessToken != null) {
                loginParameters.put(LOGIN_PARAMETER_ACCESS_TOKEN, accessToken);
                return loginParameters;
            } else {
                return null;
            }
        } catch (Exception e) {
            Log.e(Database.TAG, "Error looking login parameters for site", e);
        }
        return null;
    }

    public String loginPathForSite(URL site) {
        return "/_facebook";
    }

    @Override
    // Added per https://github.com/couchbase/couchbase-lite-java-core/issues/41
    public HttpClientFactory getHttpClientFactory() {
        return null;
    }

    public synchronized static String registerAccessToken(String accessToken, String email, String origin) {

        List<String> key = new ArrayList<String>();
        key.add(email);
        key.add(origin);

        if (accessTokens == null) {
            accessTokens = new HashMap<List<String>, String>();
        }
        Log.d(Database.TAG, "FacebookAuthorizer registering key: " + key);
        accessTokens.put(key, accessToken);

        return email;

    }


    public static String accessTokenForEmailAndSite(String email, URL site) {
        try {
            List<String> key = new ArrayList<String>();
            key.add(email);
            key.add(site.toExternalForm().toLowerCase());
            Log.d(Database.TAG, "FacebookAuthorizer looking up key: " + key + " from list of access tokens");
            return accessTokens.get(key);
        } catch (Exception e) {
            Log.e(Database.TAG, "Error looking up access token", e);
        }
        return null;
    }

}
