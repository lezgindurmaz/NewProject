#define COBJMACROS
#include <windows.h>
#include <exdisp.h>
#include <mshtml.h>
#include <oleidl.h>
#include <stddef.h>
#include "index_html.h"

// Minimal OLE Container Implementation
typedef struct {
    IOleClientSite IOleClientSite_iface;
    IOleInPlaceSite IOleInPlaceSite_iface;
    IOleInPlaceFrame IOleInPlaceFrame_iface;
    LONG refCount;
    HWND hwnd;
} WebHost;

// IUnknown for IOleClientSite
HRESULT STDMETHODCALLTYPE Host_QueryInterface(IOleClientSite* This, REFIID riid, void** ppvObject) {
    WebHost* host = (WebHost*)This;
    if (IsEqualIID(riid, &IID_IUnknown) || IsEqualIID(riid, &IID_IOleClientSite)) {
        *ppvObject = &host->IOleClientSite_iface;
    } else if (IsEqualIID(riid, &IID_IOleInPlaceSite)) {
        *ppvObject = &host->IOleInPlaceSite_iface;
    } else {
        *ppvObject = NULL;
        return E_NOINTERFACE;
    }
    ((IUnknown*)*ppvObject)->lpVtbl->AddRef((IUnknown*)*ppvObject);
    return S_OK;
}
ULONG STDMETHODCALLTYPE Host_AddRef(IOleClientSite* This) { return InterlockedIncrement(&((WebHost*)This)->refCount); }
ULONG STDMETHODCALLTYPE Host_Release(IOleClientSite* This) { return InterlockedDecrement(&((WebHost*)This)->refCount); }
HRESULT STDMETHODCALLTYPE Host_SaveObject(IOleClientSite* This) { return E_NOTIMPL; }
HRESULT STDMETHODCALLTYPE Host_GetMoniker(IOleClientSite* This, DWORD dwAssign, DWORD dwWhichMoniker, IMoniker** ppmk) { return E_NOTIMPL; }
HRESULT STDMETHODCALLTYPE Host_GetContainer(IOleClientSite* This, IOleContainer** ppContainer) { return E_NOTIMPL; }
HRESULT STDMETHODCALLTYPE Host_ShowObject(IOleClientSite* This) { return S_OK; }
HRESULT STDMETHODCALLTYPE Host_OnShowWindow(IOleClientSite* This, BOOL fShow) { return S_OK; }
HRESULT STDMETHODCALLTYPE Host_RequestNewObjectLayout(IOleClientSite* This) { return E_NOTIMPL; }

static IOleClientSiteVtbl ClientSiteVtbl = { Host_QueryInterface, Host_AddRef, Host_Release, Host_SaveObject, Host_GetMoniker, Host_GetContainer, Host_ShowObject, Host_OnShowWindow, Host_RequestNewObjectLayout };

// IOleInPlaceSite implementation
HRESULT STDMETHODCALLTYPE InPlace_QueryInterface(IOleInPlaceSite* This, REFIID riid, void** ppvObject) {
    return Host_QueryInterface((IOleClientSite*)((char*)This - offsetof(WebHost, IOleInPlaceSite_iface)), riid, ppvObject);
}
ULONG STDMETHODCALLTYPE InPlace_AddRef(IOleInPlaceSite* This) { return Host_AddRef((IOleClientSite*)((char*)This - offsetof(WebHost, IOleInPlaceSite_iface))); }
ULONG STDMETHODCALLTYPE InPlace_Release(IOleInPlaceSite* This) { return Host_Release((IOleClientSite*)((char*)This - offsetof(WebHost, IOleInPlaceSite_iface))); }
HRESULT STDMETHODCALLTYPE InPlace_GetWindow(IOleInPlaceSite* This, HWND* phwnd) { *phwnd = ((WebHost*)((char*)This - offsetof(WebHost, IOleInPlaceSite_iface)))->hwnd; return S_OK; }
HRESULT STDMETHODCALLTYPE InPlace_ContextSensitiveHelp(IOleInPlaceSite* This, BOOL fEnterMode) { return S_OK; }
HRESULT STDMETHODCALLTYPE InPlace_CanInPlaceActivate(IOleInPlaceSite* This) { return S_OK; }
HRESULT STDMETHODCALLTYPE InPlace_OnInPlaceActivate(IOleInPlaceSite* This) { return S_OK; }
HRESULT STDMETHODCALLTYPE InPlace_OnUIActivate(IOleInPlaceSite* This) { return S_OK; }
HRESULT STDMETHODCALLTYPE InPlace_GetWindowContext(IOleInPlaceSite* This, IOleInPlaceFrame** ppFrame, IOleInPlaceUIWindow** ppDoc, LPRECT lprcPosRect, LPRECT lprcClipRect, LPOLEINPLACEFRAMEINFO lpFrameInfo) {
    WebHost* host = (WebHost*)((char*)This - offsetof(WebHost, IOleInPlaceSite_iface));
    *ppFrame = &host->IOleInPlaceFrame_iface;
    *ppDoc = NULL;
    GetClientRect(host->hwnd, lprcPosRect);
    GetClientRect(host->hwnd, lprcClipRect);
    lpFrameInfo->cb = sizeof(OLEINPLACEFRAMEINFO);
    lpFrameInfo->fMDIApp = FALSE;
    lpFrameInfo->hwndFrame = host->hwnd;
    lpFrameInfo->haccel = NULL;
    lpFrameInfo->cAccelEntries = 0;
    return S_OK;
}
HRESULT STDMETHODCALLTYPE InPlace_Scroll(IOleInPlaceSite* This, SIZE scrollExtant) { return E_NOTIMPL; }
HRESULT STDMETHODCALLTYPE InPlace_OnUIDeactivate(IOleInPlaceSite* This, BOOL fUndoable) { return S_OK; }
HRESULT STDMETHODCALLTYPE InPlace_OnInPlaceDeactivate(IOleInPlaceSite* This) { return S_OK; }
HRESULT STDMETHODCALLTYPE InPlace_DiscardUndoState(IOleInPlaceSite* This) { return S_OK; }
HRESULT STDMETHODCALLTYPE InPlace_DeactivateAndUndo(IOleInPlaceSite* This) { return S_OK; }
HRESULT STDMETHODCALLTYPE InPlace_OnPosRectChange(IOleInPlaceSite* This, LPCRECT lprcPosRect) { return S_OK; }

static IOleInPlaceSiteVtbl InPlaceSiteVtbl = { (void*)InPlace_QueryInterface, (void*)InPlace_AddRef, (void*)InPlace_Release, InPlace_GetWindow, InPlace_ContextSensitiveHelp, InPlace_CanInPlaceActivate, InPlace_OnInPlaceActivate, InPlace_OnUIActivate, InPlace_GetWindowContext, InPlace_Scroll, InPlace_OnUIDeactivate, InPlace_OnInPlaceDeactivate, InPlace_DiscardUndoState, InPlace_DeactivateAndUndo, InPlace_OnPosRectChange };

// IOleInPlaceFrame implementation
HRESULT STDMETHODCALLTYPE Frame_QueryInterface(IOleInPlaceFrame* This, REFIID riid, void** ppvObject) {
    return Host_QueryInterface((IOleClientSite*)((char*)This - offsetof(WebHost, IOleInPlaceFrame_iface)), riid, ppvObject);
}
ULONG STDMETHODCALLTYPE Frame_AddRef(IOleInPlaceFrame* This) { return Host_AddRef((IOleClientSite*)((char*)This - offsetof(WebHost, IOleInPlaceFrame_iface))); }
ULONG STDMETHODCALLTYPE Frame_Release(IOleInPlaceFrame* This) { return Host_Release((IOleClientSite*)((char*)This - offsetof(WebHost, IOleInPlaceFrame_iface))); }
HRESULT STDMETHODCALLTYPE Frame_GetWindow(IOleInPlaceFrame* This, HWND* phwnd) { *phwnd = ((WebHost*)((char*)This - offsetof(WebHost, IOleInPlaceFrame_iface)))->hwnd; return S_OK; }
HRESULT STDMETHODCALLTYPE Frame_ContextSensitiveHelp(IOleInPlaceFrame* This, BOOL fEnterMode) { return S_OK; }
HRESULT STDMETHODCALLTYPE Frame_GetBorder(IOleInPlaceFrame* This, LPRECT lprectBorder) { return E_NOTIMPL; }
HRESULT STDMETHODCALLTYPE Frame_RequestBorderSpace(IOleInPlaceFrame* This, LPCBORDERWIDTHS pborderwidths) { return E_NOTIMPL; }
HRESULT STDMETHODCALLTYPE Frame_SetBorderSpace(IOleInPlaceFrame* This, LPCBORDERWIDTHS pborderwidths) { return E_NOTIMPL; }
HRESULT STDMETHODCALLTYPE Frame_SetActiveObject(IOleInPlaceFrame* This, IOleInPlaceActiveObject* pActiveObject, LPCOLESTR pszObjName) { return S_OK; }
HRESULT STDMETHODCALLTYPE Frame_InsertMenus(IOleInPlaceFrame* This, HMENU hmenuShared, LPOLEMENUGROUPWIDTHS lpMenuWidths) { return E_NOTIMPL; }
HRESULT STDMETHODCALLTYPE Frame_SetMenu(IOleInPlaceFrame* This, HMENU hmenuShared, HOLEMENU holemenu, HWND hwndActiveObject) { return S_OK; }
HRESULT STDMETHODCALLTYPE Frame_RemoveMenus(IOleInPlaceFrame* This, HMENU hmenuShared) { return E_NOTIMPL; }
HRESULT STDMETHODCALLTYPE Frame_SetStatusText(IOleInPlaceFrame* This, LPCOLESTR pszStatusText) { return S_OK; }
HRESULT STDMETHODCALLTYPE Frame_EnableModeless(IOleInPlaceFrame* This, BOOL fEnable) { return S_OK; }
HRESULT STDMETHODCALLTYPE Frame_TranslateAccelerator(IOleInPlaceFrame* This, LPMSG lpmsg, WORD wID) { return E_NOTIMPL; }

static IOleInPlaceFrameVtbl InPlaceFrameVtbl = { (void*)Frame_QueryInterface, (void*)Frame_AddRef, (void*)Frame_Release, Frame_GetWindow, Frame_ContextSensitiveHelp, Frame_GetBorder, Frame_RequestBorderSpace, Frame_SetBorderSpace, Frame_SetActiveObject, Frame_InsertMenus, Frame_SetMenu, Frame_RemoveMenus, Frame_SetStatusText, Frame_EnableModeless, Frame_TranslateAccelerator };

LRESULT CALLBACK WindowProc(HWND hwnd, UINT uMsg, WPARAM wParam, LPARAM lParam) {
    switch (uMsg) {
        case WM_DESTROY: PostQuitMessage(0); return 0;
        case WM_KEYDOWN: if (wParam == VK_ESCAPE) DestroyWindow(hwnd); return 0;
    }
    return DefWindowProc(hwnd, uMsg, wParam, lParam);
}

int WINAPI WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, LPSTR lpCmdLine, int nCmdShow) {
    OleInitialize(NULL);

    WNDCLASS wc = {0};
    wc.lpfnWndProc = WindowProc;
    wc.hInstance = hInstance;
    wc.lpszClassName = "YokiBankWebView";
    wc.hCursor = LoadCursor(NULL, IDC_ARROW);
    RegisterClass(&wc);

    int sw = GetSystemMetrics(SM_CXSCREEN);
    int sh = GetSystemMetrics(SM_CYSCREEN);

    HWND hwnd = CreateWindowEx(WS_EX_TOPMOST, "YokiBankWebView", "YokiBank-Safe Banking", WS_POPUP | WS_VISIBLE, 0, 0, sw, sh, NULL, NULL, hInstance, NULL);
    if (!hwnd) return 0;

    WebHost host;
    host.IOleClientSite_iface.lpVtbl = &ClientSiteVtbl;
    host.IOleInPlaceSite_iface.lpVtbl = &InPlaceSiteVtbl;
    host.IOleInPlaceFrame_iface.lpVtbl = &InPlaceFrameVtbl;
    host.refCount = 1;
    host.hwnd = hwnd;

    IOleObject* pOleObject = NULL;
    IWebBrowser2* pWebBrowser = NULL;

    if (SUCCEEDED(CoCreateInstance(&CLSID_WebBrowser, NULL, CLSCTX_INPROC_SERVER, &IID_IOleObject, (void**)&pOleObject))) {
        pOleObject->lpVtbl->SetClientSite(pOleObject, &host.IOleClientSite_iface);
        RECT rc; GetClientRect(hwnd, &rc);
        pOleObject->lpVtbl->DoVerb(pOleObject, OLEIVERB_INPLACEACTIVATE, NULL, &host.IOleClientSite_iface, 0, hwnd, &rc);
        pOleObject->lpVtbl->QueryInterface(pOleObject, &IID_IWebBrowser2, (void**)&pWebBrowser);
    }

    if (pWebBrowser) {
        VARIANT vEmpty; VariantInit(&vEmpty);
        BSTR bstrURL = SysAllocString(L"about:blank");
        pWebBrowser->lpVtbl->Navigate(pWebBrowser, bstrURL, &vEmpty, &vEmpty, &vEmpty, &vEmpty);
        SysFreeString(bstrURL);

        READYSTATE rs;
        do {
            MSG msg;
            while (PeekMessage(&msg, NULL, 0, 0, PM_REMOVE)) { TranslateMessage(&msg); DispatchMessage(&msg); }
            pWebBrowser->lpVtbl->get_ReadyState(pWebBrowser, &rs);
            Sleep(1);
        } while (rs != READYSTATE_COMPLETE);

        IDispatch* pDisp = NULL;
        if (SUCCEEDED(pWebBrowser->lpVtbl->get_Document(pWebBrowser, &pDisp)) && pDisp) {
            IHTMLDocument2* pDoc = NULL;
            if (SUCCEEDED(pDisp->lpVtbl->QueryInterface(pDisp, &IID_IHTMLDocument2, (void**)&pDoc)) && pDoc) {
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

                pDoc->lpVtbl->write(pDoc, sa);
                pDoc->lpVtbl->close(pDoc);
                pDoc->lpVtbl->Release(pDoc);
                SafeArrayDestroy(sa);
            }
            pDisp->lpVtbl->Release(pDisp);
        }
    }

    MSG msg;
    while (GetMessage(&msg, NULL, 0, 0)) { TranslateMessage(&msg); DispatchMessage(&msg); }

    if (pWebBrowser) pWebBrowser->lpVtbl->Release(pWebBrowser);
    if (pOleObject) {
        pOleObject->lpVtbl->Close(pOleObject, OLECLOSE_NOSAVE);
        pOleObject->lpVtbl->Release(pOleObject);
    }
    OleUninitialize();
    return 0;
}
