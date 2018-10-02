package com.brouken.palabre.ttrss;

import android.util.Base64;

import com.android.volley.AuthFailureError;
import com.android.volley.Response;
import com.android.volley.toolbox.JsonObjectRequest;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class JsonObjectRequestBasicAuth extends JsonObjectRequest {
    public JsonObjectRequestBasicAuth(int method, String url, JSONObject jsonRequest, Response.Listener<JSONObject> listener, Response.ErrorListener errorListener) {
        super(method, url, jsonRequest, listener, errorListener);
    }

    public JsonObjectRequestBasicAuth(String url, JSONObject jsonRequest, Response.Listener<JSONObject> listener, Response.ErrorListener errorListener) {
        super(url, jsonRequest, listener, errorListener);
    }

    @Override
    public Map<String, String> getHeaders() throws AuthFailureError {
        if (TinyExtension.prefBasicAuth) {
            Map<String,String> headers = new HashMap<>();
            final String credentials = TinyExtension.prefHttpLogin + ":" + TinyExtension.prefHttpPassword;
            final String auth = "Basic " + Base64.encodeToString(credentials.getBytes(), Base64.NO_WRAP);
            headers.put("Authorization", auth);
            return headers;
        } else
            return super.getHeaders();
    }
}
