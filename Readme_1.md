# 📰 NotiLytics — Public Demo

⚠️ This repository contains a **public, view-only demo version** of a private, team-based course project.
It is shared specifically for **Innovation Lab application review**.  
Sensitive information (API keys, credentials) has been removed.---

---

## 🔍 Project Overview
**NotiLytics** is a reactive web application built using the **Play Framework**.
It allows users to search live news articles by keywords, processes incoming data streams,
and presents enriched insights such as sentiment, readability, and word statistics.

The project emphasizes:
- Asynchronous and event-driven programming
- Actor-based system design (Apache Pekko)
- User-facing data interaction
- Team-based software engineering under real constraints

---

## 🧠 What’s Relevant for Innovation Lab
- **Reactive architecture** using actors and non-blocking streams  
- **User-centered interaction design** for exploring live information  
- **Systemic problem-solving**, from data ingestion to presentation  
- **Rapid prototyping mindset** suitable for immersive and interactive applications

---

## ⚙️ Tech Stack
| Component       | Details                                  |
|-----------------|------------------------------------------|
| Framework       | Play Framework (Java / Scala 2.13)        |
| Language        | Java 17                                  |
| Concurrency     | Apache Pekko Actors                      |
| HTTP Client    | Play WSClient                            |
| Build Tool     | sbt                                      |
| Frontend       | Scala Templates + JavaScript             |
| Testing        | JUnit 5, Mockito, JaCoCo                 |

---

## 🔑 API Configuration (Optional)
This project integrates with [NewsAPI](https://newsapi.org/).  
API keys are **not included** in this repository.

To run locally:
```bash
export NEWS_API_KEY="your_key_here"
sbt run

🧪 Testing
Unit tests mock external API calls
Coverage is measured using JaCoCo
sbt test
sbt jacoco

📌 Notes
This repository is intended for code structure and design review only.
It is not a production deployment.