# Phone Agent (v0.1)

Android app jo Accessibility Service se dusre apps ki screen padhta hai aur tap/type/scroll karta hai.
Dimaag: Gemini API (v1). v2 me local model (MediaPipe/llama.cpp).

## GitHub Actions se APK banao (PC ki zarurat nahi)
1. GitHub par naya repo banao (phone ke browser se bhi ho jata hai).
2. Is zip ke saare files upload karo (`.github` folder samet). Branch ka naam `main` rakho.
3. Repo -> **Actions** tab -> "Build APK" chalne do (ya "Run workflow" dabao).
4. Run complete hone par neeche **Artifacts** me `phone-agent-debug-apk` download karo, zip kholo, `app-debug.apk` install karo.

## Phone par setup
1. APK install karo (Unknown sources allow karna padega).
2. Android 13+: Settings -> Apps -> Phone Agent -> ⋮ -> **Allow restricted settings**.
3. App kholo -> "Accessibility settings kholo" -> **Phone Agent** ko on karo.
4. Gemini API key (aistudio.google.com se free) aur goal daalo -> **Agent start**.

## Safety
- Screen par "STOP AGENT" button aata hai; kabhi bhi dabao.
- Send/delete/pay jaise actions se pehle Allow/Deny dialog aata hai.
- Password fields me agent type nahi karta, aur unka text model ko nahi jata.
- Max 15 steps.
- Screen ka text model ko API ke through jata hai (v1). Sensitive apps (bank, OTP) par mat chalao.
