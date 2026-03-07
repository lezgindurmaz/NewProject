#include <windows.h>
#include <exdisp.h>
#include <mshtml.h>
#include <shlwapi.h>
#include "index_html.h"

#pragma comment(lib, "user32.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "oleaut32.lib")
#pragma comment(lib, "shlwapi.lib")

// Embedded resources
extern const unsigned char index_html[];
extern const unsigned int index_html_len;

LRESULT CALLBACK WindowProc(HWND hwnd, UINT uMsg, WPARAM wParam, LPARAM lParam) {
    switch (uMsg) {
        case WM_DESTROY:
            PostQuitMessage(0);
            return 0;
        case WM_KEYDOWN:
            if (wParam == VK_ESCAPE) {
                DestroyWindow(hwnd);
            }
            return 0;
    }
    return DefWindowProc(hwnd, uMsg, wParam, lParam);
}

int WINAPI WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, LPSTR lpCmdLine, int nCmdShow) {
    CoInitialize(NULL);

    WNDCLASS wc = {0};
    wc.lpfnWndProc = WindowProc;
    wc.hInstance = hInstance;
    wc.lpszClassName = "YokiBankWebView";
    wc.hCursor = LoadCursor(NULL, IDC_ARROW);

    RegisterClass(&wc);

    int screenWidth = GetSystemMetrics(SM_CXSCREEN);
    int screenHeight = GetSystemMetrics(SM_CYSCREEN);

    HWND hwnd = CreateWindowEx(
        WS_EX_TOPMOST,
        "YokiBankWebView",
        "YokiBank-Safe Banking",
        WS_POPUP | WS_VISIBLE,
        0, 0, screenWidth, screenHeight,
        NULL, NULL, hInstance, NULL
    );

    if (!hwnd) return 0;

    IWebBrowser2* pWebBrowser = NULL;
    IOleObject* pOleObject = NULL;
    RECT rc;
    GetClientRect(hwnd, &rc);

    // Create the WebBrowser control
    HRESULT hr = CoCreateInstance(CLSID_WebBrowser, NULL, CLSCTX_INPROC_SERVER, IID_IOleObject, (void**)&pOleObject);
    if (SUCCEEDED(hr)) {
        pOleObject->SetClientSite(NULL); // Simplified for this environment
        pOleObject->DoVerb(OLEIVERB_INPLACEACTIVATE, NULL, NULL, 0, hwnd, &rc);
        pOleObject->QueryInterface(IID_IWebBrowser2, (void**)&pWebBrowser);
    }

    if (pWebBrowser) {
        // Load the embedded HTML
        SAFEARRAY* sa = SafeArrayCreateVector(VT_UI1, 0, index_html_len);
        void* pData;
        SafeArrayAccessData(sa, &pData);
        memcpy(pData, index_html, index_html_len);
        SafeArrayUnaccessData(sa);

        BSTR bstrURL = SysAllocString(L"about:blank");
        VARIANT varEmpty;
        VariantInit(&varEmpty);
        pWebBrowser->Navigate(bstrURL, &varEmpty, &varEmpty, &varEmpty, &varEmpty);
        SysFreeString(bstrURL);

        IDispatch* pDisp = NULL;
        while (FAILED(pWebBrowser->get_Document(&pDisp)) || pDisp == NULL) {
            MSG msg;
            if (PeekMessage(&msg, NULL, 0, 0, PM_REMOVE)) {
                TranslateMessage(&msg);
                DispatchMessage(&msg);
            }
            Sleep(10);
        }

        IHTMLDocument2* pDoc = NULL;
        pDisp->QueryInterface(IID_IHTMLDocument2, (void**)&pDoc);
        if (pDoc) {
            pDoc->write(sa);
            pDoc->close();
            pDoc->Release();
        }
        pDisp->Release();
        SafeArrayDestroy(sa);
    }

    MSG msg;
    while (GetMessage(&msg, NULL, 0, 0)) {
        TranslateMessage(&msg);
        DispatchMessage(&msg);
    }

    if (pWebBrowser) pWebBrowser->Release();
    if (pOleObject) pOleObject->Release();

    CoUninitialize();
    return 0;
}
