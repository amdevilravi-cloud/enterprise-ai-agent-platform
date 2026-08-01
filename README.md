# enterprise-ai-agent-platform
enterprise-ai-agent-platform
I think this is the right time to split the projects. Your **Enterprise AI Platform** has become a reusable knowledge platform, and your **AI Agent Platform** should focus purely on agent execution and orchestration.

One thing I'd keep in mind is **not to build "another LangGraph" or "another OpenCode."** Instead, build a lightweight, extensible Java agent framework that integrates naturally with your existing platform.

# High-Level Architecture

```text
                    User
                      │
               REST / WebSocket
                      │
                      ▼
             AI Agent Platform
──────────────────────────────────────────────
                Agent Runtime
                      │
        ┌─────────────┼──────────────┐
        ▼             ▼              ▼
    Planner      Memory Manager   Workflow Engine
        │
        ▼
   Tool Orchestrator
        │
 ┌──────┼─────────┬─────────────┐
 ▼      ▼         ▼             ▼
REST   MCP     Local Tool    Future Tools
        │
        ▼
 Enterprise AI Platform
```

Notice something important:

The **Agent Platform doesn't know how to retrieve documents.** It delegates that to your Knowledge Platform.

---

# Technology Stack

Since your Knowledge Platform already uses Spring Boot, I'd keep the same stack.

### Core

* Java 21
* Spring Boot 3.5+
* Spring AI
* Spring Web
* Spring Validation
* Spring Boot Actuator
* Lombok

---

### Communication

* Spring WebClient
* Spring WebSocket (future)
* SSE

---

### Serialization

* Jackson

---

### Observability

* Micrometer
* SLF4J
* Logback

---

### Optional Later

* Redis
* RabbitMQ
* Kafka

Not needed initially.

---

# Suggested Project Structure

```text
ai-agent-platform
│
├── agent-runtime
│
├── planner
│
├── workflow
│
├── tools
│
├── memory
│
├── mcp
│
├── llm
│
├── model
│
├── config
│
├── api
│
├── common
│
└── util
```

Let's break each one down.

---

# agent-runtime

The heart of the platform.

Responsible for:

* Receive user request
* Maintain execution loop
* Ask planner
* Execute tools
* Observe
* Repeat
* Produce answer

Example:

```text
AgentRuntime

execute()

↓

Planner

↓

Tool

↓

Observation

↓

Planner

↓

Finish
```

---

# planner

Contains the reasoning logic.

Example interface:

```java
public interface Planner {

    AgentPlan plan(AgentContext context);

}
```

Today:

```
LLM Planner
```

Later:

```
Rule Planner

LLM Planner

Workflow Planner
```

---

# workflow

This is something most hobby projects skip.

Example:

```text
Workflow

↓

Step

↓

Condition

↓

Next Step
```

Future workflows:

* Code Review
* Bug Fix
* Documentation
* Requirement Analysis

---

# tools

One interface.

```java
Tool

execute()

↓

ToolResult
```

Examples:

```
KnowledgeSearchTool

FileReadTool

FileWriteTool

GitTool

TerminalTool

WebSearchTool

DatabaseTool
```

Every tool follows the same contract.

---

# memory

Separate from conversation memory.

Agent memory contains:

```
Goal

Plan

Completed Steps

Observations

Failures
```

This is **execution memory**, not chat history.

---

# mcp

Initially:

```
MCP Client
```

Later:

```
MCP Client

MCP Server

Tool Discovery
```

---

# llm

Abstraction over models.

```java
LLMClient

↓

Spring AI

↓

OpenAI

LM Studio

Ollama
```

Never let the planner depend directly on Spring AI.

---

# api

REST Controllers.

Initially:

```
POST /agent/execute

POST /agent/plan

POST /agent/tool

GET /agent/status
```

---

# model

Everything shared.

```
AgentRequest

AgentResponse

ToolRequest

ToolResponse

Plan

Action

Observation
```

---

# config

```
Spring AI

WebClient

Properties

MCP

Tool Registry
```

---

# common

Shared abstractions.

```
Exceptions

Enums

Constants

Interfaces
```

---

# First Interfaces I'd Create

### Agent

```java
public interface Agent {

    AgentResponse execute(AgentRequest request);

}
```

---

### Planner

```java
public interface Planner {

    AgentPlan createPlan(AgentContext context);

}
```

---

### Tool

```java
public interface Tool {

    String name();

    ToolResult execute(ToolRequest request);

}
```

---

### Tool Registry

```java
public interface ToolRegistry {

    Tool get(String toolName);

}
```

---

### Memory

```java
public interface MemoryStore {

    void save(...);

    AgentMemory load(...);

}
```

---

# Initial Tools

I'd start with only four.

```
Knowledge Search

Conversation Search

Web Search

Calculator
```

Then later add:

```
Git

Terminal

Filesystem

Database

Jira

GitHub

Slack

Email
```

---

# Execution Loop

Eventually your runtime becomes:

```text
Receive Request

↓

Planner

↓

Need Tool?

↓

Execute Tool

↓

Observe

↓

Need Another Tool?

↓

Yes

↓

Planner

↓

Final Answer
```

This is essentially the ReAct (Reason + Act) pattern, and it's a great foundation before introducing multiple collaborating agents.

---

# Milestones

### Milestone 1

* Spring Boot project
* Agent Runtime
* Planner
* Tool interface
* Tool Registry
* REST API

### Milestone 2

* Knowledge Search Tool (calls your Enterprise AI Platform)
* Conversation Tool
* Memory
* Multi-step execution

### Milestone 3

* MCP Client
* Dynamic tool discovery
* Workflow Engine

### Milestone 4

* Coding tools (filesystem, Git, terminal)
* Coding Agent

### Milestone 5

* Multi-agent orchestration

---

## One design decision I'd make from day one

Keep **agents**, **tools**, and **LLMs** completely independent:

* An **Agent** decides *what* to accomplish.
* A **Planner/LLM** decides *how* to accomplish it.
* **Tools** perform the actions.
* The **Runtime** orchestrates everything.

That separation will let you swap from LM Studio to another provider, add MCP tools, or even replace the planning strategy later without rewriting the rest of the platform. It's a clean architecture that will scale as your platform grows.
