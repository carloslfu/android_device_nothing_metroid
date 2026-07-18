package md.phone.control;

import android.os.Bundle;
import md.phone.control.IPhoneMdControlCallback;

/** Narrow fixed protocol between HOME and the platform control broker. */
interface IPhoneMdControl {
    int getProtocolVersion();
    Bundle getCapabilities();
    Bundle captureDisplay(String requestId);
    Bundle execute(in Bundle request);
    boolean showTask(in Bundle state, IPhoneMdControlCallback callback);
    void hideTask(String operationId);
}
