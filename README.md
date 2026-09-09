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

## 📁 Repository Structure

```text
RecipeApplication/
├── frontend/          # Client-side web application
├── backend/           # Spring Boot REST API
└── README.md          # Project documentation

🚀 Getting Started
Prerequisites
Java: JDK 17 or higher

Node.js: v18+ and npm

Database: Active Supabase project (PostgreSQL)

API Key: Free API Key from Spoonacular API

Backend Setup
Navigate to the backend directory:

Bash
cd backend
Configure your environment variables or update src/main/resources/application.properties:

Properties
# Supabase PostgreSQL Configuration
spring.datasource.url=jdbc:postgresql://<YOUR_SUPABASE_HOST>:5432/postgres
spring.datasource.username=<YOUR_DATABASE_USER>
spring.datasource.password=<YOUR_DATABASE_PASSWORD>

# Flyway Configuration
spring.flyway.enabled=true

# External API
spoonacular.api.key=<YOUR_SPOONACULAR_API_KEY>
Build and run the Spring Boot service:

Bash
./mvnw spring-boot:run
Flyway will automatically execute database migrations on startup.

Frontend Setup
Navigate to the frontend directory:

Bash
cd ../frontend
Install dependencies:

Bash
npm install
Start the development server:

Bash
npm start
# or npm run dev
🔒 Security Architecture
Application Level: Spring Security secures REST endpoints, restricting access based on user authentication states and roles.

Database Level: Supabase Row Level Security (RLS) enforces rules directly on PostgreSQL tables, ensuring users can only edit or delete their own custom content and comments even if API boundaries are bypassed.

📄 License
Distributed under the MIT License. See LICENSE for more information.


<ElicitationsGroup message="Would you like any further enhancements to the README?">
  <Elicitation label="Add API endpoint table" query="Can you add a table of key REST API endpoints (auth, recipes, comments) to the README?"/>
  <Elicitation label="Add frontend tech stack" query="Can you add specific frontend technologies (like React, Tailwind, Vite) to the Tech Stack section?"/>
  <Elicitation label="Add GitHub Badges" query="Can you add stylish shield badges for Java, Spring Boot, Supabase, and License at the top of the README?"/>
</ElicitationsGroup>
