package md.phone.control;

/** A stop request from the platform-owned co-pilot overlay. */
oneway interface IPhoneMdControlCallback {
    void onCancelRequested(String operationId);
    void onConfirmation(String operationId, boolean approved);
}
