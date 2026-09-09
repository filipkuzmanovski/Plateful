# 🍲 Plateful — Food Recipe & Community Platform

Plateful is a full-stack web application designed for food enthusiasts to discover, create, and share recipes. It integrates external recipe datasets with a community platform where users can post custom recipes, save favorites, and engage through interactive comments—all backed by enterprise-grade security and automated schema migrations.

---

## 🌟 Key Features

* **Recipe Discovery & Integration:** Synchronized external recipe search powered by the Spoonacular API.
* **User-Generated Content:** Create, update, and manage custom recipe listings with image handling.
* **Interactive Community:** Real-time engagement via user comments, ratings, and saved recipes.
* **Granular Security:** Dual-layer protection using Spring Security at the application level and Supabase Row Level Security (RLS) at the database level.
* **Automated Database Migrations:** Version-controlled database schema changes managed seamlessly with Flyway.

---

## 🛠️ Tech Stack

### **Backend**
* **Framework:** Java, Spring Boot
* **Security:** Spring Security (Authentication & JWT/Session management)
* **Database:** PostgreSQL (Cloud-hosted via Supabase)
* **Schema Management:** Flyway Migrations
* **External API:** Spoonacular API

### **Database & Cloud Security**
* **Database Layer:** Supabase
* **Access Control:** Row Level Security (RLS) Policies

---

🚀 Getting Started

Prerequisites

Java: JDK 17 or higher

Node.js: v18+ and npm

Database: Active Supabase project (PostgreSQL)

API Key: Free API Key from Spoonacular API

## 📁 Repository Structure

```text
RecipeApplication/
├── frontend/          # Client-side web application
├── backend/           # Spring Boot REST API
└── README.md          # Project documentation
