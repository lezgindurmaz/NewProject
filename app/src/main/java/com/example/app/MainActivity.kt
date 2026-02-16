package com.example.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Native metodu çağırarak kontrolü devret
        herSeyiBaslat()
    }

    // main.so içerisindeki fonksiyonu tanımlıyoruz
    private external fun herSeyiBaslat()

    companion object {
        init {
            // "libmain.so" dosyasını yükler (başındaki 'lib' ve sonundaki '.so' otomatik eklenir)
            System.loadLibrary("main")
        }
    }
}
