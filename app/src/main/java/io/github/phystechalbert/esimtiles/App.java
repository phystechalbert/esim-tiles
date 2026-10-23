package io.github.phystechalbert.esimtiles;

import android.app.Application;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // ISub and IEuiccController are internal; lift the hidden-API block for this process.
        HiddenApiBypass.addHiddenApiExemptions("L");
    }
}
