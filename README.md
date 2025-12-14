# 🚧 Hazard Detection Android App

A real-time **road hazard detection Android application** that identifies **potholes and speed breakers** using a **YOLOv8 deep learning model**.  
The app captures live camera frames, performs on-device inference, and stores hazard locations using **GPS and Firebase**.

---

## 📌 Features
- 📷 Real-time road hazard detection
- 🕳 Detects **Potholes** and **Speed Breakers**
- 🤖 YOLOv8 object detection model
- 📍 GPS-based hazard location tagging
- ☁️ Firebase integration for data storage
- 📱 Android application built with Kotlin

---

## 🧠 Model Details
- **Model Architecture:** YOLOv8
- **Classes:**
  - `0` → Pothole  
  - `1` → Speed Breaker
- **Input Size:** 512 × 512
- **Format Used:** ONNX
- **Performance:** ~0.87 mAP@50

> ⚠️ The trained model file is **not included** in this repository due to GitHub size limitations.

---

## 📥 Model Download
Download `hazardmodel.onnx` from:
- 🔗 Google Drive: https://drive.google.com/file/d/1ph0tpOC_Ntv0wOgGzw0ntb3xAC78yelT/view?usp=drive_link


Place the downloaded model at:
app/src/main/assets/hazardmodel.onnx


---
## 🛠 Tech Stack
### Mobile App
- Kotlin
- Android SDK
- CameraX
- Location Services (GPS)

### Machine Learning
- YOLOv8
- ONNX Runtime

### Backend / Cloud
- Firebase Realtime Database


---

### IMAGES


