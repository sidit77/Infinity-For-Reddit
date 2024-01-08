package ml.docilealligator.infinityforreddit;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import ml.docilealligator.infinityforreddit.account.Account;
import ml.docilealligator.infinityforreddit.apis.RedditAPI;
import ml.docilealligator.infinityforreddit.utils.APIUtils;
import ml.docilealligator.infinityforreddit.utils.SharedPreferencesUtils;
import okhttp3.Authenticator;
import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;
import retrofit2.Call;
import retrofit2.Retrofit;

class ApplicationOnlyAccessTokenAuthenticator implements Authenticator {
    private final Retrofit mRetrofit;
    private final RedditDataRoomDatabase mRedditDataRoomDatabase;
    private final SharedPreferences mCurrentAccountSharedPreferences;

    ApplicationOnlyAccessTokenAuthenticator(Retrofit retrofit, RedditDataRoomDatabase accountRoomDatabase, SharedPreferences currentAccountSharedPreferences) {
        mRetrofit = retrofit;
        mRedditDataRoomDatabase = accountRoomDatabase;
        mCurrentAccountSharedPreferences = currentAccountSharedPreferences;
    }

    @Nullable
    @Override
    public Request authenticate(Route route, @NonNull Response response) {
        if (response.code() == 401) {
            String accessTokenHeader = response.request().header(APIUtils.AUTHORIZATION_KEY);
            if (accessTokenHeader == null) {
                return null;
            }

            String accessToken = accessTokenHeader.substring(APIUtils.AUTHORIZATION_BASE.length());
            synchronized (this) {
                if (mRedditDataRoomDatabase.accountDao().isAnonymousAccountInserted()) {
                    mRedditDataRoomDatabase.accountDao().insert(Account.getAnonymousAccount());
                }
                String accessTokenFromSharedPreference = mCurrentAccountSharedPreferences.getString(SharedPreferencesUtils.ACCESS_TOKEN, "");
                if (accessToken.equals(accessTokenFromSharedPreference) || accessToken.equals("null")) {
                    String newAccessToken = getApplicationOnlyAccessToken();
                    if (!newAccessToken.equals("")) {
                        return response.request().newBuilder().headers(Headers.of(APIUtils.getOAuthHeader(newAccessToken))).build();
                    } else {
                        return null;
                    }
                } else {
                    return response.request().newBuilder().headers(Headers.of(APIUtils.getOAuthHeader(accessTokenFromSharedPreference))).build();
                }
            }
        }
        return null;
    }

    //For anonymous mode
    private String getApplicationOnlyAccessToken() {
        RedditAPI api = mRetrofit.create(RedditAPI.class);

        Map<String, String> params = new HashMap<>();
        params.put(APIUtils.GRANT_TYPE_KEY, APIUtils.GRANT_TYPE_INSTALLED_CLIENT);
        params.put(APIUtils.DEVICE_ID_KEY, APIUtils.DEVICE_ID);

        Call<String> accessTokenCall = api.getAccessToken(APIUtils.getApplicationOnlyBasicAuthHeader(), params);
        try {
            retrofit2.Response<String> response = accessTokenCall.execute();
            if (response.isSuccessful() && response.body() != null) {
                JSONObject jsonObject = new JSONObject(response.body());
                String newAccessToken = jsonObject.getString(APIUtils.ACCESS_TOKEN_KEY);
                mRedditDataRoomDatabase.accountDao().updateAccessToken(Account.ANONYMOUS_ACCOUNT, newAccessToken);
                mCurrentAccountSharedPreferences.edit().putString(SharedPreferencesUtils.APPLICATION_ONLY_ACCESS_TOKEN, newAccessToken).apply();
                if (mCurrentAccountSharedPreferences.getString(SharedPreferencesUtils.ACCOUNT_NAME, Account.ANONYMOUS_ACCOUNT).equals(Account.ANONYMOUS_ACCOUNT)) {
                    mCurrentAccountSharedPreferences.edit().putString(SharedPreferencesUtils.ACCESS_TOKEN, newAccessToken).apply();
                }

                return newAccessToken;
            }
            return "";
        } catch (IOException | JSONException e) {
            e.printStackTrace();
        }

        return "";
    }
}