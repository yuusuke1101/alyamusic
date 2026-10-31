package com.alya.music;

import android.app.Activity;
import android.os.CancellationSignal;
import android.webkit.WebView;

import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import org.json.JSONObject;

// Login Google native (Google melarang login OAuth di dalam WebView). Hasilnya (ID token) dikirim ke JS: window.alyaGoogle(json)
final class GoogleAuth {
    static void signIn(final Activity a, final WebView web) {
        try {
            if (BuildConfig.WEB_CLIENT_ID.isEmpty()) { reply(a, web, null, "WEB_CLIENT_ID belum diisi"); return; }
            GetCredentialRequest req = new GetCredentialRequest.Builder()
        .addCredentialOption(new GetSignInWithGoogleOption.Builder(BuildConfig.WEB_CLIENT_ID).setFilterByAuthorizedAccounts(false).build()).build();
            java.util.concurrent.Executor ex = a::runOnUiThread; // getMainExecutor() baru ada di Android 9+, ini aman untuk semua versi
            CredentialManager.create(a).getCredentialAsync(a, req, new CancellationSignal(), ex,
                    new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                        @Override public void onResult(GetCredentialResponse r) {
                            try {
                                Credential c = r.getCredential();
                                if (c instanceof CustomCredential && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(c.getType())) {
                                    reply(a, web, GoogleIdTokenCredential.createFrom(((CustomCredential) c).getData()).getIdToken(), null);
                                } else reply(a, web, null, "Jenis kredensial tidak dikenal");
                            } catch (Throwable t) { reply(a, web, null, String.valueOf(t)); }
                        }
                        @Override public void onError(GetCredentialException e) { reply(a, web, null, e.getType() + ": " + e.getMessage()); }
                    });
        } catch (Throwable t) { reply(a, web, null, String.valueOf(t)); }
    }

    private static void reply(Activity a, final WebView web, String token, String err) {
        try {
            JSONObject o = new JSONObject();
            if (token != null) o.put("idToken", token); else o.put("error", err);
            final String js = "window.alyaGoogle&&window.alyaGoogle(" + o + ")";
            a.runOnUiThread(() -> web.evaluateJavascript(js, null));
        } catch (Exception e) { }
    }
}
