#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <time.h>
#include <stdio.h>
#include <android/log.h>

#define LOG_TAG "SystemRPG_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// ==================== YAPILAR (DEĞİŞMEDİ) ====================
typedef struct {
    char name[50];
    int health, maxHealth, attack, defense, isAlive;
} Boss;

typedef struct {
    int health, maxHealth, attack, defense, xp;
} Player;

// ==================== GLOBALLER ====================
Player player = {100, 100, 20, 10, 0};
Boss bosses[8];
int currentBoss = 0;
char globalLog[4096] = "Game Started!\n"; // Ekranda görünecek metin

// ==================== LOG FONKSİYONU ====================
void AddToLog(const char* message) {
    strcat(globalLog, message);
    strcat(globalLog, "\n");
    // Log çok büyürse temizle (basit buffer yönetimi)
    if(strlen(globalLog) > 3500) strcpy(globalLog, "--- Log Reset ---\n");
    LOGI("%s", message);
}

// ==================== OYUN MEKANİĞİ (DEĞİŞMEDİ) ====================
void InitializeBosses() {
    char* names[] = {"TASK MANAGER", "CMD", "REGISTRY", "SYSTEM32", "POWERSHELL", "DEFENDER", "FIREWALL", "THIS PC"};
    int hps[] = {50, 70, 90, 150, 130, 120, 100, 300};
    for(int i = 0; i < 8; i++) {
        strcpy(bosses[i].name, names[i]);
        bosses[i].health = bosses[i].maxHealth = hps[i];
        bosses[i].attack = 15 + i*5;
        bosses[i].defense = 5 + i*2;
    }
}

// ==================== JNI KÖPRÜLERİ ====================

// Oyunu başlatan fonksiyon
JNIEXPORT void JNICALL
Java_com_example_systemrpg_MainActivity_herSeyiBaslat(JNIEnv *env, jobject thiz) {
    srand(time(NULL));
    InitializeBosses();
    AddToLog("Welcome to System Destroyer!");
    AddToLog("Mission: Simulate the destruction of Windows.");
}

// Saldırı butonuna basınca çalışan fonksiyon
JNIEXPORT void JNICALL
Java_com_example_systemrpg_MainActivity_saldiraBasildi(JNIEnv *env, jobject thiz) {
    if (player.health <= 0 || currentBoss >= 8) return;

    char buf[256];
    // Player Attacks
    int damage = (player.attack + (rand() % 10)) - bosses[currentBoss].defense;
    if(damage < 1) damage = 1;
    bosses[currentBoss].health -= damage;
    sprintf(buf, ">> You hit %s for %d damage!", bosses[currentBoss].name, damage);
    AddToLog(buf);

    if(bosses[currentBoss].health <= 0) {
        sprintf(buf, "⭐ %s DEFEATED!", bosses[currentBoss].name);
        AddToLog(buf);
        currentBoss++;
        return;
    }

    // Boss Attacks
    int bDmg = (bosses[currentBoss].attack + (rand() % 15)) - player.defense;
    if(bDmg < 1) bDmg = 1;
    player.health -= bDmg;
    sprintf(buf, "<< %s deals %d damage back!", bosses[currentBoss].name, bDmg);
    AddToLog(buf);

    if(player.health <= 0) AddToLog("💀 DEFEAT! Windows survived.");
}

// Kotlin'in ekranı güncellemek için çağırdığı fonksiyon
JNIEXPORT jstring JNICALL
Java_com_example_systemrpg_MainActivity_ekraniGuncelle(JNIEnv *env, jobject thiz) {
    char screen[5000];
    sprintf(screen,
        "STATUS:\n[ PLAYER HP: %d/%d ]\n[ TARGET: %s ]\n[ BOSS HP: %d/%d ]\n\nLOGS:\n%s",
        player.health, player.maxHealth,
        currentBoss < 8 ? bosses[currentBoss].name : "NONE",
        currentBoss < 8 ? bosses[currentBoss].health : 0,
        currentBoss < 8 ? bosses[currentBoss].maxHealth : 0,
        globalLog);
    return (*env)->NewStringUTF(env, screen);
}
