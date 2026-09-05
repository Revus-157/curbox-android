// ICurboxApi.aidl
// This is a copy of Curbox's published API contract. It MUST stay byte-for-byte
// compatible with the one inside Curbox (same package, same method order/signatures).
package neth.iecal.curbox.api;

interface ICurboxApi {
    int apiVersion();
    boolean isGranted();
    String execute(String command, in Bundle args);
    String query(String state);
    String list(String kind);
    String queryData(String dataset, in Bundle args);
}
