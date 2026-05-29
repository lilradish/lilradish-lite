# Architecture & Engineering Checklist

> **Purpose — a preliminary thinking structure, not a set of conclusions.**
> Use it to decide *what to consider next* when designing a system, and to make sure no significant option is overlooked before the real design work begins.
>
> - Each **heading is a question** to ask — not a statement to agree with.
> - Each `[ ]` is an **option to weigh** — not a task to complete, and not a recommendation to adopt.
> - Aim for **coverage**: tick what's relevant, skip what isn't, and let the unticked items provoke the questions you haven't asked yet.
> - Every choice here is **provisional** — conclusions come later.

---

## 1. Strategy

### Who is the primary user base?

- [x] Internal Employees (B2E)
- [ ] Enterprise Customers (B2B)
- [ ] Public Consumers (B2C)
- [ ] Developers / Partners (B2D — API & platform consumers)
- [ ] Machine / External Systems (M2M — system-to-system actors)

> **Decision:** B2E is the only primary user. B2D is dropped — the GitHub audience for this project is *evaluators* (recruiters, hiring managers, tech leads), not integrators, so there is no need to carry the burden of public API contracts, versioning, or SDKs. The three goals converge cleanly: personal pain-point (must really work) → GitHub showcase (clear architecture, professional docs, defensible decisions) → enterprise rollout (governable, observable, promotable). The latter two are strongly aligned, so one principle satisfies all three: **build a genuinely useful internal tool with enterprise-grade engineering rigor, and make the design-decision process public and documented.** Implication: capture every significant design decision as an **ADR** — GitHub's showcase value comes not from feature count but from *clear architecture + traceable decisions + professional docs*. (Echoed later under Quality → ADRs and in Governance.)

### What value or ROI must this system deliver?

- [x] Problem Statement & Value Proposition (the user / business problem this solves)
- [x] Success Metrics & Target Outcomes (KPIs / OKRs the system must move)
- [ ] ROI & Cost-Benefit Targets (expected return vs. build + run cost; payback horizon)
- [ ] Opportunity Cost & Investment Prioritization (why this over alternatives)
- [x] AI Value Justification (does AI materially improve outcomes vs. a simpler deterministic approach? guard against the "expensive toy")

> **Decision:**
>
> **Problem statement & value proposition.** lilradish is a *model-/execution-agnostic, self-evolving AI workflow platform*: workflows are **first-class, composable, nestable assets**, dynamically generated or reused by a **RAG-grounded Planner**; execution experience flows back as reusable assets, **compounding into a private knowledge base**. The two pains are the starting point. **(1) Execution "gear-shifting" is locked.** The same workflow should run differently under different resource constraints — manual copy-paste into a free chat when there's no budget, an expensive frontier model when there's token budget, a local small model for trivial steps — but today's tools hard-wire the execution path. Fix: *decouple execution strategy from workflow definition* so any workflow can shift freely between manual / local-SLM / cloud-LLM backends. **(2) Workflow know-how is trapped at the vendors.** Effective prompts, methods, and lessons accumulated across ChatGPT/Claude/etc. are locked inside each platform and can't be consolidated or reused as a personal asset. Fix: *reclaim that experience as a private RAG / memory asset.*
>
> **Success metrics.** ① number of interchangeable execution backends per workflow (target ≥3: manual / local / cloud); ② count of RAG-retrievable, reusable assets (Prompts, Workflows, Lessons); ③ controllable per-run cost-reduction.
>
> **AI Value Justification (passed).** Pain 1's core is *not* AI — it's a deterministic orchestration/routing layer (budget/availability → backend choice). That's a strength: it showcases engineering-architecture skill and is hype-resistant, with AI being merely one of the things orchestrated (maps to the checklist's *LLM Abstraction Layer & Model Tag Routing* and *AI Graceful Degradation* cloud→local→rule chain). Pain 2 *genuinely requires* AI — RAG, vector retrieval, and semantic memory can't be done deterministically. So the structure is healthy: half solid orchestration, half genuine AI need — exactly the "AI is the right tool, not a gimmick" judgment evaluators value.
>
> **ROI & Opportunity Cost (lightweight).** ROI reduces to plain cost-awareness (e.g. "how much API spend per month am I willing to bear"; real cost work deferred to FinOps); Opportunity Cost is a one-line "why this and not something else." Neither warrants a financial model at this stage.

### What is the expected lifecycle and scale?

- [ ] Short-Term Experiment / Campaign (disposable)
- [x] Proof-of-Concept / Pilot
- [ ] Departmental / Team Tool
- [ ] Business-Line Application
- [ ] Enterprise Core / Mission-Critical (long-lived, 5–10+ years)

> **Decision:** Dual-track — **invest at PoC scope, design toward Enterprise Core / mission-critical**, with a Departmental / Team Tool as the intermediate target. This is the anchor for the SLO section's "PoC principle" and the enterprise-rollout goal: build to PoC, design to enterprise standards.

### How central is AI to the application?

- [ ] Traditional Applications (deterministic, rule-based logic)
- [ ] AI-Augmented Applications (AI as optional, removable capability layer)
- [x] AI-Native / Agentic Applications (AI as core execution engine; removing AI disables core function)

> **Decision:** AI-Native / Agentic — *not* AI-Augmented. **The AI-Native determination rests on (1) alone** — remove the LLM and the core "dynamically generate a workflow" capability is gone; (2) and (3) widen the platform's scope but are not what make it AI-Native. **(1) Workflows are dynamically generated, not predefined.** The user supplies an intent (prompt); the system retrieves relevant knowledge (in an enterprise setting, the constraints of the various teams/departments) and *plans* an executable step-graph — an LLM Planner with RAG-supplied constraint context. This is the step from "orchestrator" into "agentic system" (maps to *Multi-step Agentic UX*, *AI Agent Frameworks* (LangGraph etc.), *Multi-Agent Topology*). **(2) Workflows can be registered as scheduled / batch jobs**, not just run once interactively — introducing a scheduling & async-execution domain (*Scheduled / Batch Processing*, *Job/Pipeline Schedulers*, *Long-Running Task Management* / durable execution, *Async/EDA for Long-Running Agentic Workflows*); this extends the system from an interactive tool into a platform that can also run unattended. **(3) Workflows are edited as a declarative DSL (Workflow as Code) — the single source of truth; the node-graph canvas is an optional V2 view, not built at PoC** (see Experience → *editor* decision). Honest consequence: the ambition is an AI-Native agentic platform — a strong résumé signal, but the complexity is real. Full shape: intent → Planner retrieves composable workflows + experience assets (Workflow/Prompt Registries + RAG) and reuses or newly generates an executable step-graph → per-step backend gear-shifting (manual / local SLM / cloud LLM) → run now or register as scheduled/batch → edited as a declarative DSL (canvas = optional V2 view) → execution experience feeds back as reusable assets, closing the loop.

### What non-functional targets (SLOs) must the system meet?

- [ ] Availability SLO (per tier)
- [x] Performance Targets (latency p50 / p95 / p99, throughput, cold-start allowances)
- [ ] Recovery Objectives (RTO, RPO)
- [x] AI-Specific SLOs (model response latency, token throughput, quality floor)
- [x] AI Determinism & Reproducibility Targets (acceptable variance in AI outputs)
- [ ] Scalability & Concurrency Targets (peak users, requests/sec, growth horizon)
- [x] Cost / Unit-Economics Targets (cost per request / token / tenant)
- [ ] Maintainability & Operability Targets (MTTR, change-failure rate, deployment frequency)
- [ ] Security Posture Targets (patch SLA, vulnerability remediation, audit coverage)

> **Decision:** PoC principle — lock only the SLOs that prove the core hypothesis or demonstrate engineering maturity; defer heavyweight availability/DR targets so an over-set SLO doesn't dig its own grave.
>
> **Cost / Unit-Economics — the soul metric (global, must-do).** The entire "gear-shift by budget" value proposition has to be proven in numbers: cost per workflow run, and a manual-vs-local-vs-cloud cost comparison. Without it the core selling point can't be quantified; this is the one to do most seriously.
>
> **AI-Specific SLO (quality floor) and AI Determinism & Reproducibility — reframed as step-level *opt-in* constraints, not global mandates.** This follows the system-wide principle **"Freedom by default, constraints by opt-in":** by default every step is free to gear-shift (manual / local / cloud) with no forced quality or consistency standard; when designing a workflow the user can tag a step — "must be reproducible" (lock model/params, fix the random seed), "quality must not fall below X" (warn or refuse before downgrading to a weaker backend), or "free to shift" (fully open). The three-way cost/quality/reproducibility trade-off becomes a per-step knob in the user's hands, not a hard system rule. (Reproducibility matters especially for scheduled/batch runs — a registered job that returns wildly different results each run is useless.)
>
> **Performance — domain-split, not uniformly lenient.** *AI domain* (LLM calls, RAG retrieval): accept inherent latency as physical reality; strategy is to *manage the experience* (streaming output, progress feedback, async), not to squeeze latency; a loose p95 (interactive first-token within seconds) suffices. *Fixed / deterministic domain* (workflow engine, router, scheduler, GUI responsiveness, state management, data access): pursue **achievable excellence** — motivation is personal learning + proof of capability, and clearly separating "AI's slowness (uncontrollable, accepted)" from "my own code's speed (controllable, must be excellent)" is one of the strongest maturity signals for an evaluator. Discipline: correct & clean first → measure with profiling → optimize hotspots with data behind it (premature optimization is a minus). So *Execution Profiling & Low-Overhead Instrumentation* is in; *Native Hot-Path (Rust/C++) / Wasm* is an optional showcase to add after the core works, guarded by the complexity budget.
>
> **Maintainability / DORA — no hard targets, but free evidence.** Use Git + CI from day one so deployment-frequency / change-failure-rate come as a free byproduct and serve as engineering-maturity evidence.
>
> **Deferred to enterprise evolution (deliberately not set):** Availability SLO (single-user PoC needs no 99.x%), Recovery Objectives (RTO/RPO — DR is an enterprise-core concern), Scalability & Concurrency targets (no single-user concurrency pressure; Worker-pool scale-out — and the core's own stateless active-active scale-out — is an architectural capability, not an SLO yet), Security Posture SLA (patch/audit — tighten at enterprise rollout; basic hygiene like no-hardcoded-secrets is done now as common sense, not as an SLO).

### What architectural paradigm will the application follow?

- [ ] Monolithic
- [x] Modular Monolith
- [ ] Microservices / Distributed Services
- [x] Event-Driven / Streaming-First
- [ ] Composable / Packaged Business Capability (MACH)
- [ ] Data & Analytics Product (data-as-a-product)
- [ ] Local-First Architecture (data processed & stored on-device first, then synced with cloud — privacy & offline resilience)

> **Decision:** Skeleton = **Modular Monolith core (Headless) + cross-machine Coordinator-Worker pool (GitLab-Runner model) + per-Worker sandbox + on-demand extracted specialized services + Flutter multi-client.** Isomorphic to GitLab — a mature, large-scale-validated paradigm, not microservices.
>
> **Modular Monolith core.** One process, clean module boundaries, in-process calls — serves fixed-domain peak performance (no network hops), stays single-dev-maintainable, and lets a module split out later without a rewrite (evolutionary/reversible). Headless: all clients consume one API contract.
>
> **Not microservices — a three-tier spectrum, optimum in the middle.** *Isolation ≠ microservices* (orthogonal axes): the real AI-safety need (runaway AI-generated code, over-reaching agent tool-calls, prompt-injection) is met by **per-Worker sandbox + per-agent least-privilege creds** inside the monolith, while microservices add only fault-domain isolation at the cost of mTLS/discovery/distributed-tx/multi-deploy — complexity that fights peak-performance and reversibility. GitLab is the empirical backing: a modular-monolith Rails core + a Worker pool (Runner) + a handful of services extracted only for proven needs (e.g. Gitaly in Go/gRPC for Git-storage performance), having explicitly refused microservices ("distributed ball of mud"). Hence three tiers: (1) **modular-monolith core** for most logic; (2) **Worker pool** for execution; (3) an **on-demand extraction door** — carve out a specialized service only when profiling proves a hotspot or a load needs strong isolation, one at a time, with data behind it (vector/text retrieval is already Rust via Qdrant/Meilisearch, so a self-written Rust candidate would be a CPU-bound piece we own — e.g., a rerank kernel, DSL compilation, or IFC label propagation over large graphs — not retrieval). PoC extracts nothing; clean module boundaries keep the door open.
>
> **Coordinator-Worker pool.** Driver is execution scale-out for registered batch / scheduled / long-running workflows — **Worker replication, not business decomposition** (the GitLab Runner / Celery / Temporal-Worker pattern: one core, N replicas of one Worker). Core (**stateless; active-active capable behind an LB, single instance at PoC for lack of load**) holds API + routing + permissions + Planner + RAG orchestration; Temporal's durable task queue is the dispatch backbone — stateless Workers run the same codebase in worker mode and long-poll the Temporal frontend for tasks; there is no separately-managed Redis/NATS dispatch queue (Valkey stays cache/session-only). **Scheduling & worker coordination are delegated to Temporal (its own HA tier), so the app core holds no singleton state.** **Cross-machine** is just deployment topology, activating: registration-token enrolment, Worker auth (token + TLS/mTLS, ZTNA across the trust boundary), **Pull** comms (Workers long-poll Temporal — firewall/NAT friendly, no inbound ports on Workers), heartbeat/health + failover/retry, **idempotency as critical** (a re-dispatched task must never double-execute a write), and **core streaming connections are LB-affine but Temporal-recoverable — the LB must be gRPC/HTTP-2-aware** (sticky streaming is recoverable, not real state). Distributed tracing and Worker auto-scaling deferred to the enterprise stage.
>
> **Per-Worker sandbox.** AI-generated code / agent tool-calls / untrusted payloads run in an isolated process / container / Wasm with restricted credentials (→ *Sandboxed Code-Interpreter Execution*, *Per-Agent Authorization*) — the actual AI-safety mechanism.
>
> **Event-Driven (local only) & Local-First (philosophy only).** EDA used only in the long-task/scheduling domain via Temporal signals/queues (no Kafka). Local-First spirit embraced (run local when possible, data user-owned — fits local models + a vendor-unlocked private memory store) but full CRDT sync is not built, hence unticked.
>
> **Client = Flutter (Web + Desktop + Android), single codebase.** Chosen for the hard Android requirement. Desktop → local-first/local-model/personal; Web → enterprise rollout (browser, no install); Android → personal mobile. Cost owned: Flutter-desktop local-LLM goes via FFI / a local service — workable.
>
> **Cross-machine Worker value.** *Enterprise:* each department deploys its own Workers with its own budget, usage caps, local models, and data permissions, sharing one core. *Personal:* Android / desktop / local machine each act as a Worker — the "no budget → local model" box is itself a cross-machine Worker.
>
> **Evolution (configured, not rewritten).** Core + Worker as dual startup modes of one process (`--mode=api` / `--mode=worker`), in-memory queue → separate Worker processes, same machine, network queue → cross-machine (add registration/auth/heartbeat) with ~zero business-code change, since the network queue + Pull are already in place.
>
> **Federation tier (Tier 2 — deployment topology).** Never a company-wide monolith: each department/team runs a **sovereign instance** (its own core + Worker pool + six Registries + PostgreSQL/Qdrant/Valkey/SeaweedFS/Meilisearch + users + Approved Primitives). Instances **federate selectively** via **Publisher-Scoped Pull**: a publisher marks a Workflow/Prompt/RAG entry's `share_scope` (private / specific peers / org-wide); in-scope consumers freely pull over **gRPC + mTLS (Pull)** (reusing the Worker transport); imports land in the consumer's Approved-Primitives **pending** queue (approve before use). Only **Workflow + Prompt + RAG** entries are shareable; **Tools/Code are not shared** — workflows reference them by **capability tag** (abstract, portable) so the consumer binds its own approved implementation (the model-tag-routing pattern reused at the tool layer → *workflow-as-contract*). Cross-instance egress reuses the IFC check (**peer-instance** is a trust tier). Result is a **three-tier isolation pyramid**: Tier 0 Worker sandbox (execution isolation) / Tier 1 cross-machine Worker pool (scale + fault) / Tier 2 federation (org sovereignty) — each tier the right tool for a different concern.
>
> **Pre-commits later sections:** *Protobuf-first / Contract-First* (→ principles, mandatory), *Cross-Platform Frameworks* = Flutter, *Channels* = Web + PC/Desktop + Android, *Job/Pipeline Schedulers* + lightweight queue, *Horizontal Auto-Scaling* (Workers only), *Sandboxed Code Execution*, *Per-Agent Authorization*, *Idempotency* (critical), Worker registration/auth/mTLS/ZTNA, *Pull* model, Temporal task-queue dispatch, Federation API + Peer Registry (Integration).

### Which channels must the system reach users through?

**Primary Interfaces:**
- [x] Large-Screen Web
- [ ] Small-Screen Web / Responsive
- [x] Mobile & Wearable Device Applications
- [x] PC / Desktop Applications
- [ ] Voice / Conversational Interface
- [ ] Virtual & Enhanced Reality Displays
- [ ] Embedded & IoT Device Applications
- [ ] Smart TV / OTT / Connected Living Room
- [ ] In-Vehicle / Automotive Infotainment (CarPlay, Android Auto)
- [ ] Digital Signage & Kiosks

**Secondary / Notification Channels:**
- [x] Email
- [ ] Instant Messaging
- [x] SMS / Push Notifications
- [ ] Third-Party Web & Social Sites
- [x] API / Headless Channel (programmatic access for partners & developers; may be a Primary Interface for B2D/M2M)
- [ ] Printed Media

> **Decision:** A single Flutter codebase covers all primary interfaces at once: **Large-Screen Web** (enterprise employees' main entry), **Mobile/Android** (the hard personal-use constraint), and **PC/Desktop** (the local-first / local-model carrier). **Small-Screen Web / Responsive** is obtained incidentally from Flutter Web (responsive by nature) — not separately invested in, hence unticked.
>
> Notification channels are a hard requirement, not optional: registered batch / scheduled jobs run unattended, so completion/failure must reach the user. **Email** = the first channel (universal, dependency-free, personal + enterprise). **Push Notifications** = the natural mobile outlet given the Android client (the entry is "SMS / Push" but **only Push is in scope — SMS is not done**). **API / Headless Channel** is in fact a primary architecture cornerstone (multi-client + Worker↔core comms + future integration), not a secondary channel. **Instant Messaging** (Slack/Teams/DingTalk) is an enterprise-evolution item — deferred, added later as one more adapter.
>
> **Design principle — Notification Channel Abstraction:** one unified notification interface with pluggable adapters (Email / Push / IM); core code unchanged when a channel is added. Reuses the same architecture philosophy as execution-backend gear-shifting (abstract interface + pluggable implementations).
>
> Not relevant: Voice, VR/AR, IoT, Smart TV, In-Vehicle, Kiosk, social, print.

### What architecture principles will guide our decisions?

- [x] API-First / Contract-First Design
- [x] Security & Privacy by Design (default-deny, least privilege)
- [ ] Cloud-Native & Managed-Service Preference (build only the differentiators)
- [x] Loose Coupling & High Cohesion
- [x] Design for Failure & Graceful Degradation
- [x] Observability by Default
- [x] Automation-First (IaC, GitOps, no manual toil)
- [x] Evolutionary / Reversible Architecture (decisions remain changeable)
- [x] Complexity Budget & Zero-Overhead Bias (minimalist design; avoid premature abstraction and framework bloat)

> **Decision — Architecture Principles Charter** (the ticked items plus three not in the list above):
> 1. **Security & Privacy by Design** — default-deny, least privilege; governs per-agent least-privilege, sandboxing, no-hardcoded-secrets, PII handling. Enterprise entry ticket.
> 2. **API-First / Contract-First** — Headless backend with **Protobuf as the single-source-of-truth contract** (stronger than OpenAPI: typed, versioned, cross-language codegen), reused across clients + Worker comms. Mandatory (forced by multi-client + Worker topology).
> 3. **Evolutionary / Reversible** — modular-monolith start, extract specialized services on demand (GitLab model).
> 4. **Complexity Budget & Zero-Overhead Bias** — minimalism; no premature distribution, no wheel-reinvention, no over-abstraction.
> 5. **Loose Coupling & High Cohesion** — clean module boundaries (keeps the extraction door open).
> 6. **Design for Failure & Graceful Degradation** — cloud→local→rule fallback chain; Worker heartbeat re-dispatch.
> 7. **Observability by Default** — measurement is the precondition for the fixed-domain peak-performance goal; unattended scheduled jobs need it for diagnosis.
> 8. **Automation-First** — Git + CI from day one (lightweight at PoC, IaC deferred to enterprise); includes automated CI license scanning; DORA metrics come free.
> 9. **Freedom by default, constraints by opt-in** *(self-originated)* — free gear-shift is the default; reproducibility / quality-floor / cost constraints are opt-in per-step knobs.
> 10. **Pluggable Abstraction** *(self-originated)* — unified interface + adapters for execution backends, notification channels, etc.; one consistent design language across the system.
> 11. **Self-Hosted & Open-Source Only** — local-first and data-sovereign: every third-party component must be pure OSI open-source or clearly commercial-use-permitted (e.g. Qdrant = Apache-2.0, Valkey = BSD, PostgreSQL = PostgreSQL License; local inference via Ollama / llama.cpp). Self-build is limited to the differentiated core (workflow engine / gear-shift routing / Planner / RAG orchestration). This reframes the checklist's "Cloud-Native & Managed-Service Preference" (left unticked): keep "build only the differentiators", reject vendor-locked managed cloud services. **License red-line:** avoid GPL/AGPL (copyleft) and SSPL/BSL/RSALv2 (source-available, commerce-restricted); verify local-model **weight** licenses are commercial-use-OK; maintain a `DEPENDENCIES.md` + automated CI license check. Pre-satisfies Governance items (open-source license compliance review, vendor lock-in & exit-cost evaluation, OSS open-washing identification). **License review is a continuous discipline, not one-time** — Redis, Elasticsearch, MongoDB, HashiCorp, and MinIO all relicensed; re-check every dependency on each upgrade. Three independent case studies, each fit for an ADR: **Redis → Valkey** (SSPL), **MinIO → SeaweedFS** (AGPL + maintenance mode), **HashiCorp Vault → OpenBao** (BUSL; the Linux-Foundation MPL-2.0 fork, adopted by GitLab).
> 12. **Information Flow Control by Construction** *(self-originated)* — data carries sensitivity tags, endpoints carry trust tags, labels propagate to derived data, and the gateway enforces `data ≤ endpoint` on egress; confidential data is structurally unable to leave to an untrusted endpoint regardless of prompt-injection or config. (Detailed under Data → categories.)
> 13. **Approved Primitives, Composable Workflows** *(self-originated)* — every executable atomic operation (tool / MCP / code / prompt / model) is human-approved + registered before use; AI / Planner / DSL only *compose* approved blocks → safety by construction. (Detailed under Logic → Autonomy & Safety.)
> 14. **Federated Sovereignty over Centralized Operation** *(self-originated)* — no company-wide monolith; each org unit runs a sovereign instance, federating selectively via mTLS with import-approval. (Detailed under Architecture Paradigm.)
> 15. **Identity Delegation + Passwordless** *(self-originated)* — the system is an IdP *consumer*, never an IdP: no built-in enterprise IAM / RBAC / org management; identity, roles, and permissions are 100% delegated to the deployer's IdP via standard protocols (OIDC at PoC, SAML adapter on demand). Personal mode uses **WebAuthn / Passkey** (passwordless). Business identity (delegated) is strictly separated from **infrastructure identity** (mTLS; certs consumed from an external CA or bundled EJBCA, not self-issued; OpenBao manages keys — for Worker / federation). Net effect is a *by-construction* property: **the system never stores or processes user passwords** — enterprise passwords live in the IdP, personal credentials are device-local private keys. (Detailed under Security → Identity.)
>
> **Three safety/governance pillars** emerge from 12–14: **Approved Primitives** (what AI can use) + **IFC / Approved Egress** (where data can go) + **Federated Sovereignty** (each unit autonomous).

### How will we source the application?

- [x] Custom-Built / Bespoke Development
- [ ] Packaged Vendor Application
- [ ] SaaS Subscription
- [x] External Integrated Application
- [ ] Low-Code / No-Code Platform
- [ ] Legacy Modernization / Mainframe Re-platforming

> **Decision:** Self-build the differentiated core (workflow engine / gear-shift routing / Planner / RAG orchestration); integrate ready-made pure-OSS components (Qdrant / Valkey / PostgreSQL) for everything else. Packaged vendor apps, SaaS subscriptions, and low-code platforms are excluded by principle 11 (vendor lock-in). Legacy modernization is N/A — greenfield.

### How will we govern vendors and external dependencies?

- [ ] Decision matrix applied for sourcing (TCO, Time-to-Market, Core vs. Context)
- [ ] SaaS vendor data sovereignty assessment (GDPR / US CLOUD Act conflict)
- [x] AI API vs self-hosted model TCO analysis (token economics vs. infrastructure cost)
- [x] AI model provider agnosticism design (unified API abstraction layer enabling cross-provider failover without core code changes)
- [ ] AI provider data-usage terms & training opt-out review (does the vendor train on our prompts / data?)
- [x] AI provider model deprecation & version sunset risk
- [ ] Internal Developer Platform (IDP) strategy: build / buy / managed (Backstage, Cortex, Port)
- [x] Vendor lock-in and exit cost evaluation
- [ ] Source code escrow & contractual exit / data-portability clauses
- [ ] Vendor financial viability & business continuity assessment
- [ ] Third-party / fourth-party (sub-processor) risk assessment
- [x] Open-source license compliance review
- [x] Technology lifecycle and deprecation standards
- [ ] Vendor governance framework

> **Decision:** **Gear-shift backends = three coexisting, none lockable:** cloud closed models (GPT / Claude / Gemini — used when quality is the priority, paying is justified) + local/open models (cheaper, private, offline) + manual copy-paste (no-budget fallback). All three sit behind one abstraction (principle 10), so **provider agnosticism** is mandatory — GPT/Claude/Gemini are hot-swappable, no core-code change. **Model deprecation / version-sunset risk** is neutralized by **Model Tag Routing**: a step either matches a tag (REASONING/FAST/etc. — picks any available model with that tag, naturally survives a sunset) or pins a specific model (exact reproducibility); this is "Freedom by default, constraints by opt-in" at the model layer and pre-locks the Logic-section item *LLM Abstraction Layer & Model Tag Routing*. **AI-API-vs-self-hosted TCO** is the gear-shift's own cost motive. Open-source license compliance, vendor lock-in/exit-cost, and technology-lifecycle/deprecation are satisfied by principle 11. **AI provider data-usage / training opt-out** is not the tool's concern — the deploying enterprise's vendor contracts own that; the tool only exposes which endpoint/account to route to. IDP strategy, code escrow, vendor financial-viability, and sub-processor risk are not applicable to a self-hosted open-source tool that isn't operated as a service.

### Which regulatory and compliance regimes constrain the system?

- [ ] EU AI Act applicability classification (Prohibited / High-Risk / Limited-Risk / Minimal-Risk)
- [ ] Sector-specific regulation mapping (GDPR, HIPAA, PCI-DSS, SOX, DORA, FedRAMP, APPI, FISC)
- [ ] AI transparency & disclosure obligations (AI-generated content labeling, AI-interaction notice — EU AI Act Art. 50)
- [x] Data residency and cross-border transfer restrictions
- [x] Sovereign / air-gapped deployment requirement (DORA-regulated industries)
- [ ] Records retention & legal-hold / eDiscovery obligations

> **Decision:** Positioning = **software provider, not service operator** (open-source code + setup support, no SaaS). Compliance responsibility therefore rests with the deploying organization (it is the data controller/processor; data stays on its own infrastructure), not with this project — the same boundary as PostgreSQL not owning how a hospital stores records. Stance = **Compliance-Ready Architecture: enable compliance, don't own it.** Don't build compliance features at PoC, but reserve mounting points so any requirement can plug in without refactor — a unified event hook (audit), an output-layer metadata field (AI-generated-content labeling). These are derivatives of principles 10 (pluggable), 7 (observability), 3 (reversible), so near-zero PoC cost. **Data residency** and **sovereign / air-gapped deployment** are naturally satisfied by local-first + pure-OSS self-host (a strength, not a burden). EU AI Act classification and sector regulation mapping are borne by the deployer per its use; AI-transparency labeling and records-retention are architecture-ready enabling capabilities deferred to the enterprise stage.

### What are our sustainability and carbon obligations?

- [ ] CSRD / ESRS E1 Scope 3 emissions reporting obligation assessment
- [ ] Carbon budget baseline for AI workloads (training + inference)
- [x] Model selection: carbon cost vs. accuracy trade-off (Green AI decision gate)
- [ ] Carbon-aware scheduling & region selection (low-carbon grid times / regions)
- [ ] Embodied carbon & hardware lifecycle / e-waste accounting
- [ ] Water usage efficiency (WUE) for AI data-center cooling

> **Decision:** Not a SaaS operator, runs no data center, trains no models → carbon reporting (CSRD), water-use (WUE), hardware lifecycle, carbon-aware region scheduling, and training carbon budget are all N/A, with responsibility following the infrastructure to the deployer. **Green AI model selection** is naturally realized by the gear-shift mechanism — a local small model is both cheaper and lower-carbon, so the cost/quality knob is also a carbon/quality knob — a zero-cost bonus, no extra work.

---

## 2. Architecture

### Experience

#### How will the client render content and handle interaction?

- [ ] Composition & Rendering Strategy (CSR / SSR / SSG / ISR / streaming SSR / micro-frontends)
- [x] Session & State Management (client/server state boundary, persistence)
- [ ] Client-Side Data Fetching & Caching (query caching, optimistic updates, revalidation)
- [ ] Conversation & Navigation Management (routing, deep-linking, history)
- [x] Multi-step Agentic UX Patterns (progress disclosure, interruption, recovery)
- [x] AI-Generated Content Rendering & Citation Display (token streaming, markdown/code, source anchors)
- [ ] Real-Time & Collaborative UI (live updates, presence, conflict resolution)
- [ ] Local-First / Offline Sync State Management (CRDTs / OT / event-sourcing — realizes the Strategy → Local-First paradigm)
- [x] Media Rendering & Streaming
- [ ] Form Management
- [ ] Input Validation
- [x] Optimistic UI & Loading / Error State Handling (skeletons, retries, rollback)
- [ ] Gesture Management & Manipulation
- [ ] Document & Page Generation (PDF / print / export)

> **Decision:** Editor = **Workflow as Code** — a **pure-declarative DSL is the single source of truth**; a canvas is an optional V2 view over it, not a PoC burden. The DSL only orchestrates (steps, data flow, model tags, opt-in constraints) and executes no logic itself; custom logic is **externalized to three pluggable capabilities — user MCP / user API / sandbox-registered code** (the last reuses the Worker sandbox, deferred at PoC). This is principle 10 applied to workflow steps. **Execution-state UX (ticked):** multi-step progress / interruption / resumption (the front face of the back-end checkpoint + event model); AI streaming render + RAG citation (gRPC server-streaming, markdown/code, source anchors); basic loading/error states. **Media (ticked):** workflow steps handle image/audio/video, PoC starts with images. Downstream pre-records (locked now to avoid DSL/model/storage rework): model-tag system gains modality dimensions (VISION/AUDIO; gear-shift constrained to capable models); media stored as blobs in self-hosted object storage (SeaweedFS), workflows pass references not payloads; DSL data-flow supports a media-reference type. **Always-online, single-user-primary:** no offline, no CRDT/OT (local-first ≠ offline sync); real-time collaboration deferred behind a state-layer hook. Form/Input collapse into DSL schema validation; rendering strategy (CSR via Flutter), data fetch/caching, routing, and document export are later technical details; gesture management N/A (no canvas at PoC).

#### How will the presentation adapt across devices, users, and contexts?

- [ ] Multi-Channel Presentation Adaptation (per-channel layout & capability tailoring)
- [x] Responsive & Adaptive Layout (breakpoints, fluid grids, container queries)
- [ ] Theming & Design Tokens (dark/light mode, brand theming, density)
- [ ] Localization Architecture (i18n, RTL, locale / number / date / timezone formats; modular asset lazy-loading for bundle size)
- [ ] Accessibility (WCAG 2.2, ARIA, screen-reader & keyboard navigation)
- [ ] User Customization (user-configured preferences & layouts)
- [ ] Personalization & Targeting (system-driven content per user / segment)
- [ ] Multi-Variant & A/B Testing (experiment-driven presentation variants)

> **Decision:** Responsive layout comes free from Flutter (multi-platform). **Localization** is deferred at PoC but architecture-ready — strings go through resource keys, never hardcoded, so adding a language is just a translation file (near-zero-cost "ready" stance). **Accessibility** (WCAG) is an enterprise-evolution item — not built at PoC, not blocked. Theming/design-tokens are a later technical detail. User customization, personalization/targeting, and A/B testing are not relevant (always-online, single-user-primary).

#### What UI frameworks and front-end technology will we build on?

- [ ] UI / UX Frameworks (React, Vue, Angular, Svelte; MVC / MVVM, Islands Architecture)
- [x] Cross-Platform / Multi-Channel Frameworks (React Native, Flutter, Capacitor — shared codebase across channels)
- [ ] Design System & Component Library (design tokens, primitives, Storybook)
- [ ] UI Layout & Template Management (layout composition, theming slots)
- [ ] Build & Bundling Toolchain (Vite, Webpack, Turbopack, type-checking)
- [ ] Web / Edge Server Runtime (Nginx, Node, edge functions)

> **Decision:** **Flutter** — one codebase covering Web / Desktop / Android, chosen for the hard Android requirement. Design system, build toolchain, and web runtime are implementation details.

#### How will we manage and deliver content?

- [ ] Web Content Management / Headless CMS / DXP (decoupled vs. coupled)
- [ ] Portal, Collaboration & Digital Experience Platforms (DXP)
- [ ] Structured Content Modeling (content types, reusable blocks)
- [ ] Content Preview & Editorial Workflow (draft → review → publish)
- [ ] Content Syndication & Distribution (omnichannel publishing & delivery)

#### Which AI-powered interaction modalities will we offer?

- [ ] Conversational AI & Chatbot Platforms
- [x] Agentic AI Interaction Frameworks (multi-step task delegation UX)
- [ ] Computer Vision Services (image / object / scene understanding)
- [ ] Speech Recognition & Synthesis (STT / TTS, voice UX)
- [ ] Content Recognition & Interpretation (OCR, image, voice — extracting structured meaning from media input)
- [ ] AR / VR / Spatial Interfaces
- [x] Multimodal Interaction (combined text + voice + vision input/output)

> **Decision:** **Agentic AI Interaction Frameworks** is the project's UX paradigm (multi-step workflow delegation). **Multimodal Interaction** is confirmed (image/audio/video; PoC starts with images). Discrete cloud-service modalities — Computer Vision, STT/TTS, OCR — are not built as such; multimodal capability is reached through VISION/AUDIO model-tag routing over local/cloud models. Conversational chatbot and AR/VR are not relevant.

#### How will we build user trust and control into AI interactions?

- [ ] Explainability & Trust UX (confidence scores, citation anchors, Chain-of-Thought visibility for AI-generated content)
- [ ] Deterministic Fallback UX (UI handoff from non-deterministic AI output to rule-based / human flow when confidence or guardrails breached — esp. high-risk domains)
- [ ] User Correction & Override Affordances (edit / regenerate / undo AI output)
- [ ] User Feedback Loop Integration (explicit: thumbs up/down; implicit: dwell/copy — drives RLHF / DPO alignment)

> **Decision:** Trust & control are **structurally guaranteed by Approved Primitives + step-level opt-in constraints** (see Logic → Autonomy & Safety) — the four items are left unticked only because their **UI expression** (explainability, fallback handoff, correction, feedback) is deferred to runtime-UX detailing, not because the guarantees are absent.

### Logic & Automation

#### How will business logic be executed?

- [ ] Custom Code Execution (services, functions, language runtimes)
- [x] Workflow / Orchestration Execution (BPMN, state machines, sagas)
- [ ] Rules Execution (parameterized rules, rules engine / BRE)
- [ ] Decision Tables / Decision Models (DMN — declarative decisioning)
- [ ] Complex Event Processing / Stream Processing (pattern detection over event streams)

> **Decision:** The system *is* a workflow engine, so **Workflow / Orchestration Execution** is the core (declarative DSL → engine task graph, state-machine execution). Custom Code Execution exists only as a reserved sandbox "code node" capability, not implemented at PoC. Rules engines, DMN decision tables, and complex-event/stream processing are not applicable — the workflows are discretely triggered orchestrations, not rule-bases or continuous streams.

#### How will we keep execution reliable, consistent, and recoverable?

- [ ] Atomic / Transactional Execution (ACID; saga compensation + dead-letter fallback when compensation itself fails)
- [ ] Consistency Model Boundaries (strong vs. eventual consistency per data domain)
- [ ] Data Reconciliation & Audit (automated cross-system reconciliation + audit logging)
- [ ] Resource Locking & Concurrency Control (optimistic vs. pessimistic)
- [x] Idempotency & Exactly-Once Semantics (incl. LLM tool calls — a hallucinated retry must not double-execute a write)
- [x] Timeout & Deadline Propagation (esp. cascading LLM / agent calls)
- [x] Retry Strategy & Exponential Backoff (with jitter)
- [x] Circuit Breaker & Bulkhead Patterns
- [x] Interrupted Execution Restart / Recovery (checkpointing, durable execution)
- [x] Exception & Error Handling Strategy
- [x] AI Graceful Degradation Architecture (fallback chain: cloud LLM → local SLM → rule-based)
- [x] Async / EDA Pattern for Long-Running Agentic Workflows (EDA or CQRS to decouple multi-minute agent tasks from client timeouts)
- [x] Execution Profiling & Low-Overhead Instrumentation (hot-path / latency profiling; prefer compile/load-time bytecode (ASM) over reflection / heavyweight AOP in hot paths)

> **Decision:** Reliability is mostly supplied by **Temporal** (the engine chassis): idempotency (enforced — critical, since a re-dispatched task must never double-write), retry + exponential backoff, timeouts/deadlines, and checkpoint/resume (interrupted-execution recovery) are built-in. **Circuit breakers** wrap external model-API calls; **bulkheads** are realized by Worker-type pools (local-model / cloud-model / CPU-heavy) plus the per-Worker sandbox. **AI Graceful Degradation (cloud→local→rule)** is a core feature sharing the SAME routing abstraction as budget gear-shifting — one mechanism (tag match → candidate list → pick one), two trigger sources: active (user choice) and passive (failure fallback), with the rule tier as the deterministic floor. Async/EDA decouples long agent tasks from client timeouts; Execution Profiling is in (data-driven optimization of the fixed domain). Exception handling is realized through these mechanisms. Deferred: formal Saga / atomic transactions (Temporal supports them on demand; PoC workflows are mostly read+generate); Failover / DR / multi-region (enterprise-core); Chaos engineering (evolution; Temporal auto-retry already covers basic self-healing). Consistency boundaries, cross-system reconciliation, and resource locking are not core at PoC.

#### What processing and timing models will the system use?

- [x] Synchronous / Request-Response Processing
- [x] Scheduled / Batch Processing (cron, windowed jobs)
- [x] Queued & Asynchronous Processing (message queues, task workers)
- [ ] Real-Time & Near-Real-Time Processing (low-latency event handling, micro-batch)
- [x] Streaming Processing (SSE, WebSocket, LLM token streaming)
- [x] Long-Running Task Management (durable jobs, progress tracking, resumability)
- [ ] Backpressure & Flow Control (rate limiting, buffering under load)

> **Decision:** **Synchronous** request-response only for interactive actions (open / edit / save / trigger a workflow). Workflow execution itself is **Queued & Asynchronous** — returns an ID immediately and runs on the Worker pool, since long tasks would otherwise time out. **Scheduled / Batch** is a core requirement (registered cron / batch jobs). **Streaming** = gRPC server-streaming for LLM token output. **Long-Running Task Management** = durable jobs with progress tracking + resumability. Real-time/near-real-time and backpressure/flow-control are not core at PoC.

#### How will we distribute, scale, and make execution highly available?

- [ ] Clustering (active-active / active-passive)
- [ ] Work Partitioning & Sharding (by key / tenant / range)
- [ ] Load Balancing (round-robin, least-connection, consistent hashing)
- [x] Horizontal Auto-Scaling (scale-out triggers: CPU / queue-depth / RPS)
- [ ] Failover & Failback (multi-AZ / multi-region high availability)
- [ ] Redundant Execution (N-way redundancy for critical paths)
- [ ] Multi-Version Parallel Execution (runtime canary / shadow / A-B serving)

> **Decision:** **Horizontal Auto-Scaling applies to Workers now**; the core is **stateless and active-active capable** but runs as a **single instance at PoC** (no load pressure) — *not* an architectural limit. Scale-out is triggered by queue depth, replicating the stateless Worker. **Clustering / Load Balancing are not enabled at PoC but the architecture is already ready (the core is stateless)**; sharding, failover/failback, redundant execution, and multi-version serving are enterprise-evolution items.

#### Which automation and orchestration platforms will we use?

- [ ] Business Rule Engines (BRE / DMN runtimes — e.g., Drools)
- [x] Workflow Orchestration Engines (Temporal, Conductor, Camunda 8 — durable orchestration, not legacy BPM)
- [x] Job / Pipeline Schedulers (Airflow, Dagster, cron)
- [ ] Robotic Process Automation (RPA) (UI-level task automation)
- [ ] AI Agent Frameworks (LangGraph, Spring AI Agent, Microsoft MAF, CrewAI)

> **Decision:** Orchestration engine = **Temporal** (Apache-2.0, self-hostable) as the execution chassis — durable orchestration, checkpoint, retry, timeout, concurrency, plus a Worker-pool model that *is* the GitLab-Runner-style cross-machine Worker pool decided earlier (no separate Worker coordination to build; Saga/checkpoint/retry come built-in). **Job/Pipeline scheduling** is covered by Temporal built-in. **Engine-adapter boundary (principle 10):** the DSL and all upper layers (Planner, API, Worker business logic) stay engine-agnostic — no Temporal concepts (Activity, Workflow function) leak upward; Temporal is only the current implementation behind the adapter, so self-building or swapping the engine later rewrites only the adapter, not the DSL or core. Business Rule Engines and RPA are not applicable. **No agent framework adopted (by design)** — the system's own composition (Temporal + declarative DSL + LangChain4j + the six Registries + per-Worker sandbox + Approved Primitives) already constitutes a more disciplined agent system than any single framework provides. LangGraph conflicts with the DSL-as-supervisor (and Temporal supersedes its durable-execution value); CrewAI / AutoGen's free-negotiation breaks reproducibility; Spring AI is now ecosystem-native (Java) but its agent abstraction is superseded by DSL-as-supervisor + Temporal's durable execution (same reason as LangGraph); MAF remains ecosystem-mismatched. This is a deliberate non-adoption, not an unmade choice. (LlamaIndex is re-evaluated as a RAG tool in Data, not as an agent framework.)

#### What language runtimes and frameworks will we build on?

- [x] Language Runtimes & Virtual Machines (JVM, CLR, Node, Python, Go)
- [x] Application Servers & Hosting Runtime
- [x] Application Frameworks (Spring, .NET, Express, FastAPI)
- [x] Persistence Frameworks (ORMs, query builders)
- [ ] Batch Frameworks
- [x] AI / ML SDKs & Toolkits (Spring AI, LangChain4j, LlamaIndex)

> **Decision:** Backend language = **Java (JVM)** — confirms the profiling note's JVM lean (ASM / reflection / AOP). **Hosting runtime = Armeria** (Apache-2.0, principle 11): a single process on a single port natively serves **gRPC** (mobile / desktop / Worker / federation) + **gRPC-Web** (browser) + **REST** — Envoy-sidecar functionality in-process, no proxy. **Application framework = Spring Boot 3** (via `armeria-spring-boot3-starter` for DI / config / autoconfiguration). **Persistence = Spring JdbcClient** (fluent facade over `JdbcTemplate`, in `spring-jdbc` — zero new dependency, SQL-first, maps to records; no heavy ORM, no jOOQ codegen). **AI / ML SDK = LangChain4j** (provider abstraction + embeddings + streaming + structured output; Apache-2.0) behind the internal model-call interface. **Batch Frameworks not adopted** — scheduling/batch is Temporal's job. The Armeria + Spring Boot 3 + JdbcClient combo is an ADR candidate.

#### Where will we cross language boundaries for performance?

- [ ] Cross-Language Interop & FFI Boundaries (when/where to cross runtime boundaries)
- [ ] Native Hot-Path Components (Rust / C++ for latency-critical paths)
- [ ] WebAssembly (Wasm) Integration Points (sandboxed cross-language modules)

> **Decision (backfill — cross-references, nothing built at PoC):** FFI is the one concrete boundary — Flutter-desktop local-LLM via FFI / a local service (Strategy → Channels). A native hot-path (Rust/C++) is the on-demand extraction-door candidate for self-owned CPU-bound logic (rerank kernel / DSL compilation / IFC label propagation — Architecture Paradigm), guarded by the complexity budget. Wasm is the per-Worker sandbox option (Autonomy & Safety). Zero-copy serialization is a deferred optional hot-path showcase (Data → process & transform). All deferred; the dedicated boxes stay unticked, decisions live in the cross-referenced sections.

#### How will we select, route, and adapt foundation models?

- [x] LLM Abstraction Layer & Model Tag Routing (app-side: REASONING / FAST / EMBEDDING tags)
- [x] Foundation Model Selection & Governance (proprietary API vs. self-hosted open-weight)
- [x] Multimodal Model Selection (text / vision / audio / video models)
- [x] Model Adaptation Strategy (prompt-engineering vs. RAG vs. fine-tuning vs. continued pre-training)
- [ ] KV-Cache Management (PagedAttention / RadixAttention reuse — cut TTFT & compute for multi-step agents)

> **Decision:** **Self-build the top abstraction** (the differentiated core): tag routing, gear-shift / degradation decision, opt-in constraints, token-budget check, cost accounting. Tags span **REASONING / FAST / EMBEDDING / VISION / AUDIO** (modality dims added for multimodal); a step matches a tag (free / sunset-proof) or pins a model (reproducible). **Provider-agnostic:** GPT / Claude / Gemini + local are hot-swappable. **Borrow open-source for the commodity layer:** provider abstraction sits behind an internal **model-call interface** (same engine-adapter pattern as Temporal) so churn stays isolated and the choice reversible; **JVM realization = LangChain4j** (in-process commodity translation layer; no Python sidecar); **LiteLLM Proxy is reserved as a swappable behind-the-interface alternative**. Business code never imports the provider lib directly. **Why LangChain4j over a LiteLLM Proxy:** the Proxy would duplicate the self-built LLM Gateway (routing / budget / caching) and is blind to our tag / IFC / opt-in-constraint model, whereas LangChain4j is an in-process JVM translation layer with no sidecar — fitting the complexity budget + lightweight self-deploy + same-stack. **Local inference runtime = Ollama** (default; pure-OSS, broad model ecosystem). **Model adaptation = prompt-engineering + RAG only; no fine-tuning** (the core is orchestration + RAG, not training). OSS open-washing screening per principle 11. KV-cache management is a self-hosted-inference optimization deferred to the enterprise stage. *Forward:* token-budget controls + prompt/response caching + cost/observability formalize in Integration → AI Gateway (semantic & latency-aware caching/routing are evolution items); embedding-model management lands in Data.

#### How will we construct and manage prompts and context?

- [x] Prompt Registry & Lifecycle Management (DRAFT → UNDER_REVIEW → ACTIVE → DEPRECATED)
- [ ] Context Window Management & Information Compression
- [ ] Context Window Optimization (counter "lost in the middle"; LongLLMLingua-style token-budget compression)
- [ ] Few-Shot Example Selection (static vs. dynamically-retrieved exemplars)
- [x] Structured Output Enforcement & Schema Validation (JSON schema / function-call constraints)
- [ ] Structured-Output Failure Handling (after retry exhaustion: throw vs. best-effort JSON-repair / fuzzy parse)
- [ ] Semantic Cache Strategy (whether & when to cache; exact-match vs. embedding-similarity threshold — enforced at the AI Gateway)

> **Decision:** **Prompt Registry is a core, standalone component** — it directly answers the founding pain (reclaim prompt know-how from vendors into a centrally-managed asset). It carries versioning (diff/rollback), templates + composition (variables, fragments), system-prompt / persona entries, and per-call observability (version / input / output / tokens / quality). **DSL ↔ prompt = reference-primary** (`prompt_id`), embedding allowed only as an expedient — so editing one prompt updates every workflow that uses it. **Architecture link (key):** the Registry *is* part of the RAG / Memory system — effective prompts + their context + evaluation results are sequestered there, letting RAG surface past-successful prompts for new tasks; this is where the two founding pains (gear-shift + experience-capture) first connect into the closed loop. **Structured Output Enforcement & schema validation** is in (the Planner emits declarative DSL constrained by schema, retried on mismatch); the structured-output layer is wrapped behind an internal interface — JVM implementation = LangChain4j structured output or self-built schema-validation + retry, not bound to a specific library (same depth-deferral pattern as Temporal and the model-call interface). Prompt-injection defense is required (principle 1 — formalized in Security). **Deferred (architecture-ready):** Prompt Evaluation / regression testing — PoC reserves a `test_cases` field per prompt + routes observability through the unified event hook, so an evaluator plugs in later as a consumer without changing the Registry. **Evolution items:** A/B testing, prompt compression, context-window management/optimization, dynamic few-shot selection, and semantic caching; structured-output exhaustion policy TBD.

#### How will we structure multi-agent topology and coordination?

- [x] Multi-Agent Topology Selection (Supervisor-Worker / Peer-to-Peer / Hierarchical)
- [ ] Agent Topology Fault Isolation Assessment (Supervisor-Worker SPOF mitigation; subtree-scoped fault containment for Hierarchical)
- [x] Agent Sub-task Sandboxing (per-subtask isolated execution context preventing cascade failure)
- [x] Agent Memory & State Management (shared memory vs. message passing; hierarchical summarization for hybrid state)

> **Decision — Structured Agent Orchestration ("workflow as the spine, agent as the muscle").** The deterministic **DSL + workflow engine is the supervisor**, not an LLM; agents are workers that act only inside designated steps and never freely negotiate. This beats free-negotiating multi-agent on the things that matter here: collaboration path is visible (it *is* the DSL graph), reproducible (DSL determinism + step-level opt-in constraints), and governable (each step is an isolated task with independent authorization). Two topology layers: **Planner layer** = Single-Agent to start (intent → retrieve → emit DSL), with Reflection reserved (cheap self-repair on schema-validation failure); **Execution layer** = Pipeline/Sequential as described by the DSL. Supervisor/Hierarchical not needed (the DSL already is the supervisor); Peer-to-Peer/Swarm rejected (breaks reproducibility); Debate/Critique is an evolution item. Agent state passes via **DSL data flow (message-passing), no shared mutable agent memory**; this also collapses A2A — agents exchange through the DSL data flow, so no separate A2A protocol layer is needed.
>
> **Workflow composability / nesting (Workflow Registry).** Workflows are **first-class composable assets**, not just execution units: a new core component, the **Workflow Registry** (parallel to the Prompt Registry, a higher-order experience asset bundling prompts + model choices + data flow + constraints). The DSL gains two step types: **call a sub-workflow** (reference a Registry workflow) and **call the Planner** (generate a sub-workflow at runtime) — both stay purely declarative (the DSL declares "nest a sub-flow"; generation/execution is the engine's job). Temporal **Child Workflow** supplies the nesting mechanism for free. Requires: recursion-depth / loop defense (Temporal-supported), sub-workflow version pinning (same rules as prompt references), and a workflow **I/O schema (signature)** so a parent can call a child like a typed function. This completes the **self-evolving closed loop**: intent → Planner retrieves Workflow + Prompt Registries + RAG → generate-new or reuse-fixed workflow → execute → experience feeds back as reusable assets → Planner sees more next time. Positioning: *a self-evolving AI workflow platform whose workflows are first-class composable assets that compound into a private knowledge base.*

#### How will we bound agent autonomy and ensure safety?

- [x] Autonomy Boundary Definition (read-only vs. write operation split per agent)
- [x] Agent Identity & Per-Agent Authorization (least-privilege credentials)
- [x] Human-in-the-Loop Integration Points (approval gates for high-risk actions)
- [ ] Multi-Agent Simulation & PoV Gate (pre-implementation safety validation for agentic systems)

> **Decision — core safety model: "Approved Primitives, Composable Workflows" (capability-based security + pre-approved building blocks).** The power to *author* a capability and the power to *execute* one are separated, and authoring requires human approval. **Every atomic operation — tools, MCP, sandbox code, prompts, models — must be human-approved and registered before it can be used; nothing AI-generated runs directly.** The Planner / DSL / agents can only *compose* approved building blocks, never create new executable ones. This is **safety by construction**, not runtime detection: AI-generated code can't run loose, prompt-injection can't reach a non-allowlisted tool, and a runaway Planner can at worst emit a *useless* workflow (its toolbox contains only approved primitives) — never a dangerous one. **AI promotion path:** AI proposes a candidate → it enters a draft/pending state (not referenceable) → human approves → it enters a Registry and becomes referenceable (or is archived). Human-led, AI-augmented; the system grows by approval throughput, not unchecked.
>
> **Registry system = the approval gates:** Prompt / Tool-MCP / Code / Workflow / Model / **Lessons** registries (six) all require approval (versioned, revocable); RAG/Memory raw data does not execute, so no approval (traceable only). The DSL's safety check is purely structural — every referenced ID must exist and be in "approved" state, no AI judgment involved.
>
> **Bindings (all step-level opt-in, sharing the same constraint mechanism as reproducibility / quality):** **Autonomy level** and **HITL approval gates** are DSL step properties (default freest; Temporal wait-for-signal implements pause-for-approval). **Per-agent authorization** = approved Registry entry ∩ agent credential. **Irreversible-action gate** = a Registry entry's own "red-line" tag (forces approval, not user-opt-outable). **Tool-call validation, output validation, audit trail, kill switch** are all satisfied for free: validation = "must be an approved entry" + entry schema; audit = approval history + execution reference records; kill = Temporal cancel + revoking a Registry entry stops every workflow referencing it. **Multi-Agent Simulation / PoV gate and Drift Detection** are evolution items — the Registry approval flow is itself the natural pre-production safety-eval entry. (Formalizes downstream Security/Integration items: Sandboxed Code-Interpreter Execution, Human Approval Gate for sensitive MCP calls, MCP tool-definition integrity.)

### Data

#### What categories of data will the system handle?

- [x] Functional / Business Data (core domain entities)
- [x] Operational & Telemetry Data (logs, metrics, events, audit trails)
- [x] Metadata & Configuration Data
- [ ] AI Training & Fine-Tuning Data
- [x] AI Inference Input / Output Data (prompts, completions, embeddings)
- [ ] Synthetic Data (generated for testing / augmentation / privacy)

> **Decision:** Categories present: **Functional/Business** (the six Registries, workflow defs, run history, users/permissions); **Operational & Telemetry** (prompt-call records, cost tracking, profiling, Worker heartbeat, plus the heavy **audit trail** from Approved Primitives — who approved which entry, which run referenced which entries); **Metadata & Configuration** (incl. the sensitivity/trust labels below); **AI Inference I/O** (prompts, completions, **vector embeddings** = the RAG/Memory core). Also handled but owned by other subsections: media blobs (object storage), cache (Valkey), and **secrets/credentials** (many — model API keys, Worker mTLS certs, MCP creds, agent creds; handled seriously per principle 1, formalized in Security). No AI Training/Fine-Tuning data (no training); no Synthetic data at PoC.
>
> **Graph data → PostgreSQL, no graph DB.** Registry cross-references, workflow nesting, agent×primitive authorization, and RAG→prompt→workflow provenance are only 1–3-hop "light graph" queries — expressible with PostgreSQL recursive CTE / JSONB / joins (GitLab's dependency graphs work this way). A graph DB's cost (extra store, query language, ops) violates the complexity budget for a benefit not needed. (Storage-tech selection formalized in the next subsection.)
>
> **PII / sensitive data → Information Flow Control (IFC) by construction** (principle 12, system-wide): every datum carries a **sensitivity tag** (public / internal / confidential; user-uploaded files default to **internal** — strict default), every egress channel (model endpoint, MCP tool, API, **peer instance**) carries a **trust tag** (untrusted / trusted-contracted / trusted-private / peer-instance), **labels propagate** to derived data (file → chunk → embedding → prompt; multi-source takes the strictest), and the **LLM Gateway enforces an egress check** before every call (`input_max_sensitivity ≤ endpoint_trust`, else refuse / downgrade to a trusted endpoint / HITL). Confidential data is *structurally* unable to reach an untrusted endpoint regardless of prompt-injection or workflow config — this is **Approved Egress**, the dual of Approved Primitives. Reuses existing mechanisms: the routing layer's 4th trigger dimension (alongside capability tags, active gear-shift, failure-degradation); Model/Tool Registries gain a `trust_level` field; the DSL gets a step-level `required_max_egress` opt-in. The deployer configures the tagging policy (Compliance-Ready).

#### How will we model our data?

- [x] Conceptual Model (entities & relationships)
- [x] Logical Model (normalized schema, attributes, keys)
- [x] Physical Model (storage-specific implementation)
- [x] Schema Evolution & Versioning (backward / forward compatibility, migrations)

> **Decision:** All six Approved-Primitives registries share **one universal entry schema**: `id` (global UUID), `type`, `name`, `version`, `status` (state machine DRAFT → PENDING_REVIEW → APPROVED → DEPRECATED → REVOKED — this is the canonical status vocabulary; template checkbox labels' "UNDER_REVIEW / ACTIVE" are synonyms for PENDING_REVIEW / APPROVED, and body text, ADRs, and architecture.md standardize on these canonical names), `content` (JSONB, per-type), `capability_tags`, `sensitivity` (IFC), `share_scope` (federation), `audit` (created/approved/revoked by+at), `references` / `referenced_by` (impact analysis = field query, not table scan). **Migrations:** **Flyway Community** (Apache-2.0; plain `V1__xxx.sql`, Spring Boot auto-run), not invented. **Persistence:** **Spring JdbcClient** (fluent over `JdbcTemplate`; zero new dependency; SQL-first; no heavy ORM, no jOOQ codegen); SQL covered by integration tests. **Revocation cascade = severity-tagged** (`revocation_severity`): critical / security → immediate cascade (stop running workflows, block new); deprecated / superseded → grace period (warn, finish in-flight, block new), superseded adds an upgrade hint. **DSL versioning = N-1 execution support + all-version read support**: the engine *executes* current + previous version (deprecation window, GitHub/GitLab-style) but can *read/parse* every historical version (Episodic/audit replay); federation imports outside the N-1 range get a `requires_migration` flag before approval.

#### How will we classify data by role and usage?

- [x] Transactional Data (OLTP)
- [ ] Reporting & Analytical Data (OLAP)
- [x] Master Data (canonical business entities — MDM)
- [x] Reference Data (lookup / code tables)

> **Decision:** Functional/business + telemetry data are **OLTP** (PostgreSQL). The six Registries are the **Master Data** (canonical entities). Lookup/cache (model-tag tables, capability tags, Valkey cache) are **Reference Data**. **OLAP left unticked** — no separate analytical/warehouse estate; cost/usage metrics are lightweight, derived from operational data in PG.

#### What data structures and states must the system handle?

- [x] Structured Data (relational / tabular)
- [x] Semi-Structured Data (JSON, XML, Avro, Parquet)
- [x] Unstructured Data (documents, images, audio, video)
- [x] Data at Rest (stored)
- [x] Data in Transit (over the wire)
- [x] Data in Use (in-process, cached — confidential computing / TEE if sensitive)

> **Decision:** Structured = relational rows in PG; Semi-Structured = JSONB (DSL, payloads, config); Unstructured = media blobs in SeaweedFS + RAG documents. All three states are present: at-rest (PG/Qdrant/SeaweedFS persistence), in-transit (gRPC + mTLS over the wire), in-use (Valkey cache + working memory). **TEE / confidential computing is enterprise-deferred** — the in-use tick reflects ordinary in-process/cached data, not TEE.

#### How will we organize data semantics and meaning?

- [x] Domain Taxonomies (controlled vocabularies, categories)
- [ ] Navigation Taxonomies (faceted hierarchies)
- [ ] Ontologies (formal relationships — RDF / OWL)
- [ ] Knowledge Graph Design (entity-relationship graph; powers GraphRAG)
- [ ] Entity Resolution & Identity Mapping (dedupe / link records to canonical entities)

> **Decision:** Semantics are organized by a lightweight **`capability_tags`** taxonomy (model tags, tool/MCP capability tags, Registry types) — a controlled vocabulary, not a formal knowledge model. **Ontologies / Knowledge Graph / GraphRAG deliberately not done** (graph needs met by PostgreSQL recursive CTE; no RDF/OWL). Entity resolution N/A at this scale.

#### How will we process and transform data?

- [x] Data Access & Abstraction Layer (repositories / DAO / query APIs)
- [x] Data Validation & Cleansing (schema, type, quality rules)
- [x] Data Enrichment & Aggregation
- [ ] Data Schema Transformation & Mapping (ETL / ELT transforms)
- [x] Encoding, Serialization & Compression (incl. media encode / decode / transcode)
- [ ] Zero-Copy Serialization (FlatBuffers / Cap'n Proto for IPC / FFI hot paths)

> **Decision:** **Data access** goes through repositories / adapters (principle 10). **Validation** = DSL + payload schema checks (Planner output schema-validated, retry on mismatch). **Enrichment** = IFC label propagation + RAG context assembly. **Encoding/serialization** = Protobuf for RPC + media encode/transcode for the multimodal pipeline. **ETL/ELT transforms not a concern** (no warehouse). **Zero-copy serialization** left as an optional hot-path showcase (pairs with the deferred Rust/Wasm native path), not at PoC.

#### How will we store, index, and retain data?

- [x] Data Persistence (durable storage strategy)
- [x] Data Caching (read / write-through, TTL, invalidation)
- [x] Data Indexing (query-optimization indexes)
- [x] Data Partitioning & Segregation (sharding, multi-tenant isolation)
- [x] Data Versioning (snapshots, time-travel)
- [ ] Append-Only Immutable Event Store (event sourcing; tamper-resistant change log)
- [ ] WORM / Immutable Storage (S3 Object Lock — compliance retention for event logs & AI audit)
- [x] Data Archiving & Tiering (hot / warm / cold storage)

> **Decision:** **Persistence** = PostgreSQL (durable; pg_dump + WAL archiving / snapshots → SeaweedFS; Qdrant & Meilisearch dumps → SeaweedFS; SeaweedFS internal replication; Valkey not backed up — rebuildable). **Caching** = Valkey (TTL / invalidation). **Indexing** = PG indexes + Qdrant + Meilisearch. **Partitioning / segregation** = the **federation boundary** (each dept instance is its own data tenant) — no in-DB sharding at PoC. **Versioning** = Registry entry versions + DSL versioning. **Archiving & tiering / retention:** Tier-1 raw episodic is windowed (~90d, configurable); the six Registries + Lessons + Tier-2 episodic + RAG sources are permanent (revoked/deprecated kept for history); Valkey via TTL; Temporal state per its retention. Append-only event store, WORM / Object-Lock, and PITR / multi-region backup are enterprise-evolution.

#### Which data stores and database technologies will we use?

- [x] Relational Databases (RDBMS)
- [ ] Distributed SQL / NewSQL (CockroachDB, Spanner, TiDB)
- [ ] NoSQL Databases (key-value, document, column-family, graph)
- [x] In-Memory Databases & Caches (Redis, Hazelcast)
- [x] Vector Databases (Qdrant, Pinecone, Weaviate, pgvector)
- [ ] Time-Series Databases (telemetry, metrics)
- [ ] Data Warehouses & Lakehouses (OLAP / analytics)
- [x] Object & File Storage (S3-compatible)
- [ ] Digital Asset Management (DAM)
- [ ] Directory Services (LDAP)
- [ ] Distributed Ledgers & Blockchain
- [ ] Tamper-Evident Verifiable Ledger (Merkle-tree / hash-chained audit store — financial / regulatory)

> **Decision — a 5-component data plane, all open-source / clean-license / single-binary / federation-consistent:** **PostgreSQL** (relational, the main store — six Registries, users/permissions, workflow defs, runs, approval history, audit, JSONB metadata, light-graph via recursive CTE, and **time-series** telemetry/cost/profiling); **Valkey** (BSD; in-memory cache — prompt/response cache, session state, hot-path); **Qdrant** (Apache-2.0; vector — RAG embeddings, semantic retrieval, multimodal vectors); **SeaweedFS** (Apache-2.0; object storage — multimodal blobs / uploads — **replaces MinIO**, which relicensed to AGPL v3 and entered maintenance mode, violating principle 11; SeaweedFS is S3-compatible, actively maintained); plus **Meilisearch** (full-text, see next subsection). **Time-Series Databases** left unticked — no dedicated TSDB; PostgreSQL handles telemetry with **TimescaleDB** (a PG extension, zero-migration) reserved as the depth-deferred upgrade. NoSQL/document + graph DBs not needed (PG JSONB + recursive CTE cover them); Distributed SQL/NewSQL, Data Warehouse/Lakehouse, DAM, distributed ledger, and verifiable-ledger are not applicable / enterprise-evolution.

#### Which enterprise data-management and analytics platforms do we need?

- [ ] Master Data Management (MDM)
- [ ] Enterprise Content Management (ECM)
- [x] Enterprise Search Platforms
- [ ] BI & Analytics Platforms
- [ ] Reporting & Analytics Presentation (operational / embedded reports + BI dashboards & visualization)
- [ ] Media Streaming Servers
- [ ] Enterprise Archiving Platforms

> **Decision:** Search engine = **Meilisearch (MIT)**, default from PoC (not a deferred option). Reason = the **i18n-first + federation** constraints: one single binary auto-detects language and handles CJK + any language with one config, versus PostgreSQL full-text's per-language extension patchwork (zhparser / Lindera / …) that would diverge across federated instances. With Qdrant it gives **Hybrid Search by construction** (Qdrant semantic + Meilisearch keyword/multilingual + rerank — the RAG best practice) with no additional engine, and drives Registry browse/search UX (typo tolerance) + audit/log search. MDM, ECM, BI/analytics, reporting, media-streaming, and archiving platforms are not applicable to this tool (enterprise-evolution).

#### How will we ground responses with retrieval (RAG)?

- [x] RAG Pipeline Architecture (ingestion → retrieval → generation)
- [ ] Query Transformation & Rewriting (multi-query, HyDE, step-back prompting)
- [ ] Relevance Pre-filtering (lightweight scoring before expensive inference)
- [x] RAG Citation Tracking Architecture (chunk-ID → source mapping; evidence markers for traceability)

> **Decision — RAG/Memory cognitive architecture (founding pain #2 — the experience-capture loop).** Four memory types: **Semantic** (knowledge sources — Qdrant + PG + SeaweedFS + Meilisearch), **Procedural** (Workflow + Prompt Registries), **Episodic** (NEW — past-execution traces), **Working** (Temporal workflow state). **Qdrant is the unified semantic-index layer, not just RAG** — separate collections for knowledge chunks / workflow descriptions / prompt descriptions / episodic traces / lesson insights / multimodal assets — so the Planner retrieves across all memory in one pass (intent → embed → parallel-query all collections → rerank + IFC check + trim → context → DSL). **Two-tier Episodic Memory** ("capture by default, promote by opt-in"): Tier 1 raw auto-capture (on success + threshold; lower retrieval weight; windowed retention) → Tier 2 curated (user-marked OR reflection-promoted; higher weight; permanent). **Reflective memory:** after a run an LLM reflects the trace into a *lesson* that enters the Approved-Primitives queue → on approval lands in the **Lessons Registry (the 6th Registry)** and becomes Planner-retrievable. Query rewriting and relevance pre-filtering are deferred (not decided at PoC).

#### How will we design the vector & retrieval index?

- [ ] Chunking Strategy (fixed-window / structure-aware / hierarchical parent-child / semantic similarity)
- [x] Embedding Model Selection & Dimensionality Planning
- [x] Multi-modal Embedding Support (text, image, audio)
- [x] Hybrid Search Architecture (sparse BM25 + dense vector + Reciprocal Rank Fusion)
- [x] Re-ranking Layer (cross-encoder for precision tuning after retrieval)
- [ ] Incremental Ingestion & Deduplication Strategy
- [ ] Agentic RAG & Multi-hop Retrieval (intent routing, GraphRAG integration)

> **Decision:** **Embedding model is instance-local** — each instance picks its own (personal = open local model, enterprise = its own), routed through the *same* unified abstraction as LLM calls (tag / gear-shift / failure-degradation / **IFC egress**: confidential → local embedder, public → cloud allowed). **Hybrid Search** = Qdrant (dense semantic) + Meilisearch (keyword/multilingual) + rerank, applied to *all* retrieval types (knowledge, workflows, prompts, episodic), not RAG only. **Multi-modal embedding** supported (image/audio vectors). Chunking strategy, incremental-ingestion/dedup, and agentic multi-hop / GraphRAG are deferred (not decided at PoC).

#### How will we operate and govern the vector index?

- [ ] Vector Index Lifecycle Management (embedding model upgrade, drift handling)
- [ ] Vector TTL & Tiered Storage (expire / down-tier cold embeddings)
- [ ] Embedding Model Upgrade Blue-Green Re-indexing (shadow indexing + dual-write + second-level rollback)
- [ ] Vector Space Drift (Embedding Drift) Telemetry Monitoring (silent recall degradation detection)
- [ ] Vector Database Multi-tenancy Physical Isolation (partition-key isolation vs. dedicated resource groups)
- [x] Metadata Filtering & RBAC at Vector Layer (pass user auth token through to engine-enforced row-level security, not just app-layer filtering)
- [ ] Right to Erasure in Vector Databases (selective deletion of PII-linked chunks & embeddings — GDPR Art. 17)
- [x] RAG Data Lineage & Grounding Traceability (document → chunk → embedding → prompt → output)

> **Decision:** **IFC sensitivity labels live in the Qdrant payload**, enabling metadata-filtered, policy-enforced retrieval (not just app-layer). **RAG lineage/traceability** (document → chunk → embedding → prompt → output) falls out of IFC label propagation + citation tracking. **Cross-instance shares source data, never vectors** — different instances use different embedding models → incomparable vector spaces, so a receiver re-embeds locally (also IFC- and privacy-friendly; reinforces vector-space sovereignty). Vector multi-tenancy isolation is achieved by **federation** (each instance its own Qdrant), not in-DB partitioning. Vector TTL/tiering, embedding-upgrade blue-green re-indexing, drift telemetry, and GDPR right-to-erasure are enterprise-evolution.

### Integration

#### How will data and messages move between systems?

- [x] Endpoint Capability (services & APIs)
- [x] Transport & Delivery (sync request/response, async messaging)
- [ ] Transport Type Bridging / Adapters
- [x] Anti-Corruption Layer (isolate 3rd-party SDK / legacy system models from the core domain)
- [x] Publish / Subscribe (event-driven)
- [x] Message Routing (content-based / topic routing)
- [x] Sequencing & Correlation (ordering, correlation IDs)
- [ ] Service Lookup & Indirection (registry, broker)
- [x] Guaranteed Delivery (at-least-once vs. exactly-once; dead-letter queues)
- [ ] Replication & Synchronization

> **Decision:** Movement is mostly settled by earlier choices. **Endpoints + transport** = gRPC services (sync request/response interactive; async for workflow execution). **Anti-Corruption Layer** = the engine-adapter (Temporal) and the model-call interface (LangChain4j behind it) isolate every 3rd-party SDK from the core (principle 10). **Pub/Sub + Message Routing + Guaranteed Delivery** come from **Temporal** (durable task queues, step routing, at-least-once + dead-letter) over the **Pull** Worker model. **Sequencing & Correlation** = workflow ordering + correlation IDs; **exactly-once effect** achieved via **idempotency** (already locked as critical). Transport bridging N/A (gRPC-only); cross-instance is federation Pull, not data replication.

#### How will we design and govern APIs and services?

- [ ] API Gateways (REST / gRPC / GraphQL)
- [ ] API Management Platforms (lifecycle, dev portal)
- [x] API Design Standards (Protobuf single-source-of-truth contract; OpenAPI auto-generated artifact only)
- [x] API Versioning & Deprecation Strategy
- [ ] Service Mesh (mTLS, traffic policy, observability)
- [ ] Service Discovery & Registries

> **Decision:** **gRPC-only, browser-inclusive.** The single API surface is the **gRPC protocol family** — no REST, GraphQL, or WebSocket. Backend = **Armeria** (Apache-2.0) as the underlying server, integrated via `armeria-spring-boot3-starter` (Spring Boot DI/config). A single process on a single port natively serves **gRPC + gRPC-Web + REST**, giving Envoy-sidecar functionality in-process with no proxy — decisive for the federated "each department self-deploys lightweight" model. (gRPC services are registered the Armeria way, *not* via `grpc-spring-boot-starter` / Spring gRPC, which are grpc-java-based and do not serve gRPC-Web.) **Contract = Protobuf single source of truth**, superseding OpenAPI as the primary contract (OpenAPI can be auto-generated from `.proto` for external HTTP/JSON consumers like webhook subscribers — an artifact, not the contract). **Versioning** reuses the DSL N-1 policy, enforced by Protobuf evolution rules. No separate API gateway or API-management platform; no service mesh — mTLS is point-to-point (federation transport), not via a mesh.

#### What messaging and streaming infrastructure will we use?

- [ ] Message Brokers (Kafka, RabbitMQ, Pulsar)
- [ ] Event Streaming & Stream Processing (Kafka Streams, Flink)
- [x] Schema Registry & Event Contract Management (Avro / Protobuf)
- [ ] WebSocket & Real-Time Gateways
- [ ] Server-Sent Events / SSE (LLM token streaming)

> **Decision:** **Protobuf schemas (managed with the buf toolchain) are the RPC/event contract** — the same single source of truth that drives every API. **Streaming = gRPC server-streaming** for LLM tokens and workflow progress; this *replaces* SSE, so SSE and WebSocket stay unticked. Broker/stream-processing infrastructure (Kafka, Pulsar, Flink) is out of PoC scope.

#### Which integration platforms and connectors will we use?

- [ ] ETL / ELT & Data Pipeline Platforms
- [ ] Reverse ETL (warehouse → operational systems)
- [ ] iPaaS & Event Mesh
- [x] RPC Frameworks (gRPC, Connect Protocol)
- [ ] Managed File Transfer (MFT)
- [ ] Connectors & Adapters (pre-built integrations)
- [ ] Data Access Libraries (JDBC, ODBC)

> **Decision:** **RPC framework = Armeria's gRPC** over standard `protoc` / grpc-java stubs (buf toolchain unchanged) — one `.proto` source generates server + all clients, Armeria serving gRPC + gRPC-Web from it. Flutter mobile/desktop use the official `grpc` Dart package over native HTTP/2; Flutter Web uses the same package's `grpc_web` (gRPC-Web is forced by the browser — full gRPC can't control HTTP/2 framing). Worker↔Core business calls use Armeria gRPC; Temporal's SDK is gRPC internally. Other integration styles (ETL/ELT, Reverse ETL, iPaaS, MFT, prebuilt connectors, JDBC/ODBC) are not relevant to this internal-tool scope.

#### What AI gateway capabilities do we need?

- [x] AI Gateway vs. API Gateway Layer Separation (distinct architectural layers)
- [x] Multi-Model Routing & Intelligent Load Balancing (gateway-side, across providers)
- [x] Token-Based Rate Limiting & Per-Project Budget Controls
- [x] Semantic Caching (gateway-layer enforcement of the cache strategy via vector store)
- [x] Prompt Injection & Harmful Content Filtering
- [ ] PII / PHI Detection & Masking (Presidio-style, within VPC)
- [x] AI-Specific Observability Integration (token usage, TTFT, model version)
- [x] Streaming Response Handling (SSE, chunked transfer)

> **Decision:** The **self-built LLM Gateway** is a distinct top layer, separate from the (gRPC) API surface. It owns: **multi-model routing** (tag routing REASONING/FAST/EMBEDDING/VISION/AUDIO + gear-shift + cloud→local→rule degradation), **token budget + cost accounting**, **semantic caching** (via the vector store), **AI-specific observability** (token usage, TTFT, model version), and **streaming response handling** (gRPC server-streaming, not SSE). **Prompt-injection / harmful-content filtering** is required (principle 1). **PII/PHI detection & masking left unticked** — pre-tokenization send-to-cloud masking is enterprise-deferred. **Semantic caching has an architectural slot at the gateway (vector-store-backed); activation is enterprise-deferred** (consistent with the Logic → Prompt-context evolution note). The provider adapter (LangChain4j, behind the internal model-call interface) sits *under* this gateway, depth-deferred.

#### How will we deploy and expose MCP tools to agents?

- [x] MCP Server Deployment Model (embedded / sidecar / remote service)
- [x] MCP Tool Registry & Capability Discovery (tools/list)
- [ ] OpenAPI-to-MCP Tool Mapping (automated spec conversion)
- [ ] Sandboxed Code-Interpreter Execution (run agent-generated code in ephemeral, isolated containers / Wasm; resource & network limits)
- [ ] MCP Response Body Trimming (data minimization before LLM context injection)
- [ ] MCP Session Affinity (sticky routing for stateful SSE / Streamable HTTP)
- [ ] Agent-to-Agent (A2A) Communication Protocol
- [ ] Sampling (MCP Server → Client LLM delegation for server-side reasoning)

> **Decision:** **Client-only at PoC** — the system is an MCP *Client* (a Worker calls user-/enterprise-provided MCP servers as workflow steps); exposing the platform itself *as* an MCP server (so external AI tools like Claude Desktop / Cursor invoke our workflows-as-tools) is an **evolution item** (one realization of capability-based cross-instance portability). **Dual transport:** **stdio** (Worker spawns a local subprocess; for local-file / local-resource tools) **and HTTP** (remote, long-lived, enterprise-shared service) are both supported — orthogonal use cases. **stdio lifecycle = hybrid:** frequently-used servers are pooled/resident, long-tail servers spawn on demand and are reclaimed (balances cold-start latency vs. memory). HTTP servers are inherently resident. **Tool discovery via `tools/list`** feeds the Registry snapshot (see approval below). **A2A protocol not introduced** — the declarative DSL data-flow replaces direct agent-to-agent messaging. Capability negotiation is standard protocol handling, not a design point. No standalone MCP Gateway — the self-built LLM Gateway + Tool/MCP Registry already centralize tool-call governance; a standalone MCP gateway is a deferred hook (if ever needed, it is the same swappable behind-the-interface alternative noted under model selection).

#### How will we secure and govern MCP tool calls?

- [x] MCP Authentication & Token Exchange (short-lived session tokens, OIDC)
- [x] MCP Token Brokering & Phantom Token Pattern (LLM / MCP client untrusted; no raw API keys at client; token exchange at server / gateway boundary)
- [x] MCP Tool Definition Integrity & Anti-Poisoning (cryptographic verification of dynamic tool metadata; alert on schema mutations)
- [x] MCP Shadow IT Detection & Blocking (inventory unauthorized MCP servers; JSON-RPC deep packet inspection)
- [ ] Human Approval Gate for Sensitive MCP Tool Calls

> **Decision — three-layer MCP safety, enterprise-grade by construction:** every `mcp_call` passes (1) **reference must be Approved** — only servers/tools registered in the Tool/MCP Registry and approved can be invoked, so unauthorized ("shadow") MCP servers are blocked by allowlist (preventive; JSON-RPC DPI-style active detection is enterprise); (2) **schema must match** — the live server schema is re-fetched and strictly compared against the Registry archive on *every* call, rejecting on any mismatch (defeats schema-drift / tool-poisoning between calls); (3) **IFC egress check** — data sensitivity must be ≤ the server's trust tag (a public MCP server cannot receive confidential data). **Approval granularity = Server registration + Tool snapshot:** approving a server captures its full tool list at that moment; *any* change to the tool set (new tool or modified schema) triggers re-approval. **Auth:** HTTP MCP uses OAuth 2.1 (MCP standard); **token brokering** issues per-agent, least-privilege, short-lived credentials so the untrusted LLM/client never holds raw keys. Strict per-call verification is the concrete payoff of the "fixed/deterministic domain pursues excellence" SLO.

### Infrastructure

#### What compute platforms will the system run on?

**End-User Devices:**
- [ ] Client Hardware Provisioning & Fleet (managed endpoints, kiosks — device/channel choice itself: see Strategy → Channels)
- [ ] Peripherals (displays, input devices, printers, scanners)

**Back-End Servers:**
- [ ] General-Purpose & Blade Servers
- [ ] AI Accelerators (GPUs, TPUs, NPUs)
- [ ] Mainframes
- [ ] Edge Gateway Servers
- [ ] Quantum Computers

**Edge & Autonomous Devices:**
- [ ] Edge Computing Devices
- [ ] Embedded & IoT Devices
- [ ] Robots & Autonomous Systems
- [ ] Sensors & Actuators (environmental, location, biometric, RFID)

**Virtual & Containerized Compute:**
- [ ] Hypervisors & Virtual Machines
- [x] Containers & Runtimes
- [ ] Serverless / Function-as-a-Service
- [ ] Virtual Desktop Infrastructure (VDI)
- [ ] NUMA-Aware Scheduling & Memory Isolation (CPU pinning, hugepages — for latency-critical native components)

> **Decision (backfill):** **Containers** are the target deployment vehicle — Worker replicas and the per-Worker execution **sandbox** (process / container / Wasm) run as containers. PoC may run as plain processes (`--mode=worker`); Kubernetes orchestration is enterprise-deferred (left unticked below). No VMs / serverless / VDI.

#### What network architecture will the system use?

**Physical Network:**
- [ ] Switches & Routers (hubs, bridges, access points)
- [ ] Load Balancers
- [ ] Firewalls & IDS / IPS
- [ ] Cabling & Antennas
- [ ] Kernel-Bypass / Zero-Copy Networking (DPDK, SR-IOV, RDMA / RoCEv2 — hot-path latency)

**Virtual & Software-Defined:**
- [ ] Software-Defined Networking (SDN)
- [ ] Virtual Networks (VLANs, VXLANs)
- [ ] VPNs & Overlay Networks
- [x] Zero-Trust Network Access (ZTNA)

> **Decision (backfill):** **ZTNA** is the network posture (principle 1 + cross-machine Worker boundary): Worker↔Core and cross-instance traffic cross trust boundaries authenticated by **mTLS** over the **Pull** model (firewall/NAT-friendly, no inbound ports on Workers). No SDN/VLAN/VPN infrastructure assumed — trust is at the identity layer, not the network perimeter.

#### What storage architecture will the system use?

**Physical Storage:**
- [ ] Network-Attached Storage (NAS)
- [ ] Storage Area Networks (SAN)
- [ ] Direct-Attached Storage (DAS)
- [ ] Removable & Tape Media

**Virtual & Software-Defined:**
- [ ] Software-Defined Storage (SDS)
- [x] Object Storage Substrate (Ceph / MinIO / S3-compatible — physical/SDS layer; logical blob selection lives in Data)
- [ ] Distributed File Systems
- [ ] Storage Virtualization & Virtual SANs

> **Decision (backfill):** Object-storage substrate = **SeaweedFS** (self-hosted, S3-compatible, clean-license — chosen in Data over MinIO's AGPL relicense). Holds media blobs + backups; workflows pass references, not payloads.

#### What data-center and facility infrastructure is needed?

**Facility:**
- [ ] Data Center Space & Location
- [ ] Physical Access Control
- [ ] Modular Data Centers
- [ ] Carbon-Aware Region Selection (renewable grid mix)

**Equipment:**
- [ ] HVAC & Cooling (liquid cooling for AI accelerators)
- [ ] Power Supply & UPS
- [ ] Racks

### Platform

#### How will we build and run our internal developer platform (IDP)?

- [ ] Developer Portal & Service Catalog (Backstage, Cortex, Port)
- [ ] Software Catalog Completeness (services, APIs, libraries, pipelines, cloud resources)
- [ ] Golden Path / Paved Road Definitions
- [ ] Self-Service Scaffolding & Template Management
- [ ] Day-2 Operations Self-Service (config / cert / scaling / teardown)
- [ ] Golden Path Configuration Drift Detection (GitOps reconciliation + escape-hatch)
- [ ] Platform Maturity Level (CNCF Platform Maturity Model)
- [ ] Engineering Scorecard Automation (DORA + SPACE auto-calculated)
- [ ] GenAI Module Self-Service Distribution (standardized, policy-wrapped)
- [ ] Compliance & Security Guardrails Automation
- [ ] Ephemeral Environment FinOps Harvesting (TTL auto-teardown)

#### What web intermediation and edge services do we need?

- [ ] Web Caching & CDNs
- [ ] Transcoding Services
- [ ] Proxy Servers (forward / reverse)
- [ ] Access Management & SSO

#### Which operating systems must we support?

- [ ] Server Operating Systems
- [ ] Desktop Operating Systems
- [ ] Mobile Operating Systems
- [ ] Embedded & Real-Time OS (RTOS)
- [ ] Container-Optimized OS

#### Which cloud and container platforms will we use?

- [ ] Container Orchestration (Kubernetes)
- [ ] Cluster Federation (multi-cluster / cross-region scheduling for cloud neutrality)
- [ ] PaaS Platforms
- [ ] IaaS & Cloud Management Platforms (CMP)
- [ ] Multi-Cloud Networking & Connectivity (cross-cloud VPC peering / transit gateways)
- [ ] Sovereign Cloud Platforms
- [ ] Infrastructure as Code (IaC) Tools
- [ ] GitOps Tooling (Argo CD, Flux)
- [ ] FinOps Tooling & Cost Allocation

> **Decision:** Managed-cloud platforms (PaaS / IaaS / Multi-Cloud / Sovereign Cloud) are rejected by principle 11 (self-hosted, build only differentiators). **Kubernetes orchestration, IaC, and GitOps tooling are Automation-First-derived and reserved, but enterprise-deferred** — PoC ships **Git + lightweight CI** only (consistent with the Automation-First note: "IaC deferred to enterprise"). Left unticked to keep the boxes honest about PoC scope.

### Security

#### How will we manage identity and authentication?

- [x] Identity & Access Management (IAM)
- [x] Federated Identity & SSO
- [ ] Privileged Access Management (PAM)
- [x] Anti-phishing MFA for Privileged Accounts (FIDO2 / WebAuthn / PIV)
- [ ] Just-in-Time (JIT) Access Provisioning
- [ ] Zero Standing Privileges (ZSP) & Ephemeral Credentials (eliminate static long-lived secrets for service accounts & CI/CD; Managed Identity / short-lived tokens)
- [ ] Workload Identity (SPIFFE / SPIRE; dual-layer node + workload attestation)
- [ ] SVID Lifecycle Hardening (X.509 no cA flag; JWT exp ≤ 5 min + strict aud)

> **Decision — Identity Delegation (be a consumer, not a provider).** IAM is ticked to mark that identity *is* addressed — but as a **consumer / delegation** role, not by building an IdP. The system builds **no enterprise IAM / RBAC / org management** — identity, roles, and permissions are 100% delegated to the deployer's IdP (principle 15). An **IdP adapter layer exists from day one** (principle 10); adapters are added on demand: **OIDC at PoC** (modern standard, shares OAuth 2.1 infra with MCP), **SAML 2.0 as an enterprise-evolution adapter** (built for the first SAML customer). **Personal mode = WebAuthn / Passkey** (passwordless: Touch ID / Face ID / Windows Hello / YubiKey; one WebAuthn backend contract across all three Flutter targets). **MFA never blocks** — delegated to the IdP. **By-construction property: the system never stores user passwords** (enterprise → IdP; personal → device-local private keys). **Two identity layers, strictly separated:** *business identity* = IdP-delegated (who is acting; enterprise service accounts use OAuth Client Credentials, personal/debug use PATs); *infrastructure identity* = **mTLS** with certs **consumed from an external CA** (deployer's CA, or bundled EJBCA), not self-issued; OpenBao manages keys (which machine/component — Worker↔Core, federation). **RBAC / SCIM / PAM / JIT** are explicitly **not built** — enterprise IdP owns them. Recommended self-hostable IdPs for enterprise: Keycloak (Apache-2.0) / Authentik (MIT).

#### How will we enforce authorization and zero-trust?

- [x] Zero Trust Architecture (ZTA) — NIST SP 800-207 (network-layer ZTNA in Infrastructure)
- [ ] ABAC with CARTA Continuous Risk Evaluation (geolocation / device / behavior context; beyond static RBAC)
- [ ] L7 Authorization Policy Externalized (OPA / Kyverno; deny-by-default + allow-list)
- [x] mTLS & Mutual Authentication
- [ ] Service Mesh Strict mTLS Enforcement (no PERMISSIVE without expiry; secure naming validates SPIFFE ID)
- [ ] Cross-Trust-Domain Federation (Bundle Endpoint protocol; no shared Root CA)
- [ ] AUDIT Action Policy (non-blocking compliance logging; async to SIEM)

> **Decision (identity-driven items only; full authz model is a later Security pass):** the business/infrastructure identity split + everywhere-mTLS + per-agent least-privilege credentials *is* the **Zero Trust** posture. **mTLS** is the infrastructure-identity mechanism (Worker↔Core, federation), certs consumed from an external CA or bundled EJBCA (not self-issued) — not via a service mesh (Service Mesh strict-mTLS deliberately unticked; mTLS is point-to-point). Authorization decisions (RBAC) are delegated to the enterprise IdP's roles/groups; ABAC/OPA externalized policy is enterprise-evolution. Network-layer ZTNA is tracked under Infrastructure.

#### How will we detect and respond to threats?

- [ ] Endpoint Security Software (ESS / EDR)
- [ ] Intrusion Detection & Prevention (IDS/IPS — network appliances in Infrastructure)
- [ ] Web Application Firewall (WAF)
- [ ] Automated Malware Analysis (AMA)
- [ ] File Integrity Monitoring (FIM)
- [ ] Managed Detection & Response (MDR)
- [ ] Security Information & Event Management (SIEM)
- [ ] Security Analytics & UEBA (user / entity behavior analytics)

#### How will we protect data with cryptography?

- [x] Key & Certificate Management (KCM)
- [ ] Hardware Security Module (HSM) (physical / cloud-HSM root-key custody; PQC key wrapping)
- [x] Encryption at Rest & in Transit
- [ ] Field-Level / Application-Layer Encryption (column / field crypto before persistence; granular key scoping)
- [ ] Digital Signature & Non-Repudiation
- [ ] Crypto Agility (algorithm-agnostic key management)
- [ ] Post-Quantum Cryptography (PQC) Migration Readiness (ML-KEM, ML-DSA, SLH-DSA)

> **Decision — Secrets via a pluggable `SecretsProvider` interface** (principle 10; the 5th reuse of the engine-adapter pattern after Temporal / LangChain4j / IdP / the Notification-Channel abstraction). **PoC adapter = env vars / encrypted files** (SOPS + age optional, zero-dependency); **enterprise adapter = OpenBao** (MPL-2.0, Linux-Foundation Vault fork — Vault's BUSL relicense trips the principle-11 red-line; GitLab itself adopted OpenBao, reinforcing the GitLab-isomorphic narrative). Business code never holds raw secrets — it asks the interface for currently-valid credentials. Managed secrets: model API keys, Worker mTLS certs, MCP/agent creds, DB creds. **Infrastructure identity = two certificate planes** (service-entry TLS + internal service mTLS); the system is **not a CA**. **Unified source rule (personal = enterprise): consume the deployer's CA if present** (internal mTLS usually lands as a **delegated sub-CA** — enterprise CAs rarely automate short-lived workload certs; the entry plane uses their server cert), **else bundle EJBCA**, which issues both planes (chosen for the most-mature **PQC / ML-DSA** support + same Java stack; step-ca is limited — its ACME path has no PQC). **OpenBao manages keys only** — it stores/forwards certs but does not issue them. PoC = single-machine self-signed / minimal. Isomorphic to identity delegation (consume-or-bundle-fallback). OpenBao (secrets/keys) remains the **third license case study** (Redis→Valkey, MinIO→SeaweedFS, Vault→OpenBao). **Encryption:** in-transit = TLS + cross-boundary mTLS (full-path, already locked); at-rest = the deployer's infrastructure responsibility (disk/volume) **plus** application-level encryption for the highest-sensitivity items (secrets themselves, via OpenBao). **Field-level encryption left unticked** — reserved hook (triggerable by IFC `confidential` tags), enterprise-evolution; **TEE / in-use** also enterprise (Data layer). HSM is enterprise. **Crypto Agility / PQC Migration Readiness — the CA layer is already PQC-ready (EJBCA ML-DSA)**; both items stay unticked (full crypto-agility/PQC rollout is an enterprise effort), but the algorithm path is no longer a dead-end.

#### How will we prevent data loss and protect privacy?

- [ ] Data Loss Prevention (DLP)
- [ ] Dynamic Data Masking (DDM) & Row-Level Security (RLS) (query-time masking)
- [ ] Zero-Standing-Privilege Architecture (ephemeral credentials + session recording + auto-revocation)
- [ ] Non-Production Synthetic Data Mandate (Differential Privacy; no raw PII in non-prod)
- [ ] Privacy-Enhancing Technologies (PETs) (Federated Learning, SMPC, Homomorphic Encryption, DP-injection before external-LLM calls)

#### How will we secure the software and AI supply chain?

- [x] Software Bill of Materials (SBOM) Generation (CycloneDX, SPDX)
- [ ] ML-BOM Generation (model card + data provenance + weight signing)
- [ ] Artifact & Container Image Signing (Cosign / Sigstore keyless — OIDC + Fulcio + Rekor)
- [ ] OCI Attestation Chaining (SBOM + vulnerability report bound to image digest)
- [ ] Admission Control (verify signatures at deploy)
- [ ] Container Runtime Hardening (read-only root filesystem, rootless / non-privileged, dropped capabilities)
- [ ] Build Provenance Recording (SLSA level targets)
- [ ] Hermetic Build Network Isolation (prefetch dependencies; no external network at build)
- [x] Dependency Vulnerability Scanning
- [ ] Minimal Base Images (Distroless / Wolfi)
- [ ] AI Model Weight Integrity Verification
- [ ] AI Training Data Provenance (C2PA attestation; anti-poisoning traceability)

> **Decision:** Supply-chain baseline = **CI license scanning** (principle 11, already locked) + **dependency vulnerability scanning** (Trivy / Dependabot) + **SBOM generation** (CycloneDX/SPDX) — all architecture-ready, low-cost, high showcase value. Image signing / SLSA provenance / admission control / runtime hardening / ML-BOM / weight-integrity are enterprise-evolution. (License-compliance + build-time SBOM also surface under Delivery → supply chain — same decision, tick there when that chapter is walked.)

> **Security monitoring & prompt-injection note:** **Audit logging** is the unified compliance event hook (who-approved-what, which-run-referenced-what) — architecture-ready to push async to a SIEM (the `AUDIT Action Policy` item, enterprise). Active threat detection / SIEM is enterprise. **Prompt-injection blast radius is structurally bounded** (a successful injection can only compose Approved Primitives — principle 13 — and cannot exfiltrate confidential data past the IFC egress check — principle 12; worst case is a useless workflow, not a dangerous one); input-layer pattern filtering at the LLM Gateway (already ticked) is supplementary defense-in-depth, not the primary mechanism.

#### How will we manage security and fraud risk?

- [ ] Digital Risk Protection (DRP)
- [ ] Threat Intelligence Platform (TIP)
- [ ] Enterprise Fraud Management (EFM)
- [ ] AI Risk Classification (EU AI Act risk-level mapping)

#### How will we defend AI inputs and models from attack?

- [ ] Prompt Injection Defense (input sanitization, system-prompt isolation)
- [ ] Multi-layer Semantic Filter for Prompt Injection (prompt segregation + judge model + red-team)
- [ ] Jailbreak & Adversarial Input Testing
- [ ] RAG Ingestion Data Poisoning Defense (detect malicious content before it enters the vector index)
- [ ] Model Inversion & Training-Data Extraction Risk (membership inference; sensitive-data reconstruction defense)

#### How will we control AI outputs and agent behavior?

- [ ] Model Output Audit & Content Filtering
- [ ] Outbound Data Loss Prevention / LLM DLP (block sensitive-data leakage via model output)
- [ ] Outbound LLM DLP (prevent PII / source code / trade secrets leaving via prompts to external models)
- [ ] Agent Permission Boundary Enforcement (read vs. write split)
- [ ] AI Agent Runtime Behavior Baselining & Anomaly Circuit-Breaker (tool-call spikes, lateral movement)
- [ ] Immutable AI Decision Audit Trail (retrieval → prompt → invocation → output provenance; to SIEM)
- [ ] OWASP LLM Top 10 Coverage

---

## 3. Development

### Analysis & Design

#### How will we capture and manage requirements?

- [ ] Requirements Capture & Management
- [ ] Use Case / User Story Definition
- [ ] Acceptance Criteria & Definition of Done
- [ ] Requirements Validation & Traceability
- [x] Prototyping & Storyboarding (mockups, wireframes)
- [ ] As-Is Assessment & To-Be Design

> **Decision:** Solo + pain-driven → formal requirements engineering (user stories / acceptance criteria / traceability) is **N/A**. Requirements source = the two founding pains (Strategy). The GUI workflow editor carries prototyping value, but PoC substitutes **DSL-as-code** for visual editing — canvas deferred.

#### What design tools and patterns will we use?

- [ ] Process Design
- [ ] UI / UX Design Tooling
- [x] Data Modeling & Design
- [x] Architecture & Application Design Tooling (C4, ADRs)
- [x] AI System Design Patterns (RAG, Agent, Tool-Use, Context Engineering)
- [ ] Media Creation
- [ ] Environment Specification

> **Decision:** Backfill of design methodologies already decided. **C4 + ADRs** (MADR + Y-Statement, top-down architecture.md). **AI design patterns** = RAG + Structured Agent Orchestration + Tool-Use via Approved Primitives + Context Engineering via the Prompt Registry. **Data modeling** = the six-Registry universal schema. Process / UI-UX / media / environment-spec tooling not needed.

### Program & Project Management

#### How will we plan and track delivery?

- [ ] Project / Portfolio Management (PPM)
- [ ] Delivery Planning, Scope & Release Management
- [ ] Progress Tracking & Reporting
- [ ] Estimating & Forecasting (predictive analytics)
- [ ] Issue & Risk Management

#### How will we run agile delivery?

- [ ] Backlog Management & Prioritization
- [ ] Roadmap Management
- [ ] Burn-Down / Burn-Up Tracking
- [ ] Retrospective Management

#### How will we resource and enable the team?

- [ ] Staffing & Resource Planning
- [ ] Team Collaboration (tools, ceremonies)
- [ ] Training & Competency Management (incl. AI literacy)

> **Decision:** Solo project → staffing / ceremonies / training are **N/A**. Software-provider positioning (not service operator) → **no on-call**. Sole decider → RACI is implicit (ADR `Deciders` field). Low-cost showcase placeholders reserved for future collaboration: **`CODEOWNERS` + `CONTRIBUTING.md` + `CODE_OF_CONDUCT.md`** — demonstrate awareness of ownership/collaboration governance without PoC cost.

### Development Tooling

#### What developer tooling and environments will we use?

- [ ] IDE & Code Editors
- [x] AI-Augmented Coding Assistants (Copilot, Cursor, Codeium)
- [x] Local-to-Cloud Dev Environment Parity (DevContainers; Telepresence for remote-cluster debugging)
- [x] Build Tools (Maven, Gradle, npm, Cargo)
- [x] Artifact & Code Repository (Git)
- [x] Dependency Management

> **Decision:** All decided elsewhere. **AI coding assistants** in ZDR mode (Engineering Standards). **Environment parity** = Docker Compose one-command deps + optional DevContainer (Environments). **Code repository** = GitLab workshop + GitHub gallery (Release Governance). **Dependency management** = lock file + license/vulnerability scan. **Build tool = Gradle** (follows the JVM implementation language; incremental builds, integrates the Spotless / SonarQube / Spock gates). IDE is personal preference — no decision.

#### How will we manage the application lifecycle (ALM)?

- [x] Requirements & Release Management
- [ ] Test Management Platform
- [x] Manual & Automated Test Tools
- [ ] Test Data Management
- [ ] Defect Tracking & Management

> **Decision:** **Test tools** = the testing pyramid (already decided). **Release management** = dual-platform + Curated Publish Pipeline + SemVer + CHANGELOG (already decided). Solo project → no dedicated test-management / test-data / defect-tracking platform needed (GitLab built-in issues suffice).

### Engineering Standards

#### What engineering standards will we enforce?

- [x] Code Review Process
- [x] Test Coverage Requirements
- [x] Branch Strategy & Commit Standards (Trunk-Based, Conventional Commits)
- [x] Quality & Security Gating Criteria (code-quality & security gates)
- [x] DevSecOps Pipeline Standards
- [x] Documentation Standards (auto-generated vs. manual)

> **Decision:** Consolidated backfill of standards decided elsewhere. **Code review** = solo self-review + AI-assisted code through the same gates. **Coverage** = fixed-domain core only. **Branching** = GitHub Flow + Conventional Commits (GitLab-internal); the **PR is the gate point** for the 5 hard gates and is showcase-friendly (the checkbox label's "Trunk-Based" is the template example — GitHub Flow governs). **Quality/security gating** = the 5 hard gates. **DevSecOps** = license + vulnerability scanning embedded in CI. **Documentation** = three-layer assets + ADR + pure Mermaid.

#### How will we measure developer experience and effectiveness?

- [x] Developer Experience (DX) Metrics (DORA, SPACE)
- [ ] AI Impact on DORA Metrics (bi-directional: deploy frequency vs. change-failure from hallucinated APIs; internal data-ecosystem readiness)
- [ ] SPACE Framework AI Dimension Re-measurement (activity / satisfaction / efficiency in AI-assisted context)
- [ ] Developer AI Dependency Health (prevent skill atrophy; minimum human-review coverage)
- [ ] AI-Assisted Code Review Effectiveness (speed vs. defect-escape-rate trade-off)
- [ ] Value-Driven Developer Metrics (complexity-weighted throughput; replace raw LOC / PR count for individual evaluation)
- [ ] PR Flow Health (congestion >24h alert; revert rate & short-term churn; reviewer cognitive load + AI pre-screening)

> **Decision:** **DORA = a free Git + CI byproduct** (already decided). Solo project → no team-scale DX needs (PR congestion / review load / skill-atrophy); SPACE AI-dimension and the other AI-DX metrics are enterprise/team-evolution.

#### What standards govern AI-assisted development?

- [x] AI-Generated Code Review Policy (copyright ownership; mandatory security scanning)
- [x] AI Code Quality Standards (no hallucinated APIs; mandatory test coverage)
- [x] AI Coding Tool Telemetry Data Residency (ZDR; prevent prompt / code leakage to third-party training)
- [ ] AI-Generated Code Ghost Dependency Detection (hallucinated package names; supply-chain poisoning)
- [ ] AI-Generated Code Architectural Decay Monitoring (track AI-generated technical debt; GIST-style decay audit)

> **Decision — disciplined AI-assisted coding.** AI-generated code goes through the **same CI gates as hand-written code** (license + vulnerability scan, build, tests) — no exemption. **AI coding tools must run in ZDR (zero-data-retention) mode** so prompts/code aren't harvested for third-party training — IFC thinking applied at the developer-tool layer. **No dedicated ghost-dependency / slopsquatting tool** (deliberate): hallucinated/nonexistent packages are caught by compilation + dependency resolution, known-malicious packages by the vulnerability scan, and the residual slopsquatting risk (a freshly squatted name) is covered by **conscious per-dependency review** — natural in a solo project where every new dependency is intentionally added. This is judgment over security theater. Architectural-decay monitoring is enterprise.

---

## 4. Build & Test

### Build

#### How will we generate code and configuration?

- [x] Code & Configuration Authoring
- [ ] Infrastructure-as-Code (IaC) Definition
- [x] AI-Assisted Code Generation Workflow
- [ ] Code Generation & Forward Engineering (model-driven)

> **Decision:** Code authoring = conventional + AI-assisted (ZDR mode, same CI gates — Engineering Standards). **IaC enterprise-deferred** (Platform). Note: Protobuf → code generation (buf toolchain → protoc/grpc-java stubs for Armeria + Dart stubs for Flutter) is *contract-toolchain* codegen, not model-driven forward engineering — so the latter stays unticked.

#### How will we build and integrate?

- [x] Software Compilation & Validation
- [x] Linking & Packaging
- [x] Build Orchestration
- [x] Continuous Integration (CI)
- [x] Development Environment Integration
- [ ] Hermetic Build (reproducible, isolated)
- [ ] Build Provenance Recording (SLSA)

> **Decision:** **Build orchestration = Gradle** (compile / link / package / test). CI from day one (principle 8); the build produces **container images** (the locked deploy vehicle). Dev-environment integration = Docker Compose. **Hermetic build / SLSA provenance enterprise-deferred** (Supply Chain).

#### How will we manage artifacts and configuration?

- [x] Version Management
- [ ] Artifact Registry (Nexus, Artifactory, OCI)
- [x] Container Image Registry
- [ ] AI Model Artifact Registry (MLflow, W&B, DVC)
- [x] Prompt Template Versioning & Registry
- [x] Software Configuration Management (SCM)

> **Decision:** Version management = SemVer. Container image registry = the release vehicle. **Prompt template versioning IS the Prompt Registry** (core component: DRAFT→APPROVED state machine + diff/rollback) — direct mapping. SCM = Git/GitLab. No general artifact registry (container registry covers it) and **no AI model artifact registry** (prompt-engineering + RAG, no training → no model artifacts).

### Test & Evaluation

#### How will we manage and track testing?

- [x] Test Case & Script Creation & Management
- [x] Test Execution Tracking & Reporting
- [ ] Test Data Creation & Management
- [ ] Load Data Creation & Management

> **Decision:** Test cases + execution tracking = CI-built-in reporting; no standalone test-management platform (solo — ALM). Test data is lightweight at PoC (inline fixtures); **load data N/A** (no load testing).

#### How will we execute functional tests?

- [x] Unit Testing
- [x] Service & API Testing
- [x] UI & End-to-End Testing
- [ ] Multi-Channel Testing
- [x] Integration Testing
- [ ] Consumer-Driven Contract Testing (can-i-deploy gate; Pact Broker; contract tag binding)
- [ ] Mutation Testing Incremental Analysis (changed AST; nightly sidecar; coverage baseline)
- [ ] Integration Test Shared Dependency Externalization (contract-generated WireMock stubs)
- [ ] Property-Based Testing PRNG Seed Fixation (deterministic replay debugging)
- [ ] Test Framework Environment Isolation (mutant artefacts isolated from build images; Pact token sanitization)

> **Decision:** Full pyramid for the **fixed/deterministic domain** (workflow engine, router, scheduler, DSL parsing, Registry, IFC checks): **unit + service/API + integration + end-to-end** — this is the domain the SLOs hold to excellence, so it is tested strictly. **Framework = Spock** (Apache-2.0; Groovy given-when-then); **integration tests use Testcontainers** (MIT lib) to spin real PG / Valkey / Qdrant — container runtime = Podman / Colima / Linux Docker Engine (avoids Docker Desktop's commercial licensing). **AI-domain test methodology is deferred** ("再说": eval set / contract testing / LLM-as-judge / regression baseline are a deep topic to settle separately) — no architecture rework cost because the mount points are already reserved (Prompt Registry `test_cases` field + unified event hook).

#### How will we verify non-functional requirements?

**Performance & Scale:**
- [x] Performance Testing & Profiling (latency, throughput, token cost)
- [ ] Load & Stress Testing
- [ ] Soak / Endurance Testing (memory leaks, resource drift over time)
- [ ] Spike & Scalability Testing (burst capacity, autoscaling validation)

**Resilience & Recovery:**
- [ ] Chaos Engineering & Fault Injection Experiments
- [ ] Failover & Disaster Recovery Testing (RTO / RPO validation)

**Security & Compliance:**
- [x] Security Testing (SAST, DAST, dependency / SCA scan)
- [ ] Penetration Testing

> **Decision:** **Profiling** is the fixed-domain performance discipline (SLO: measure → optimize hotspots with data); **formal load / stress / soak / spike testing is enterprise-deferred** (no single-user concurrency pressure at PoC). **Security testing scope at PoC = dependency / SCA scan** (Trivy/Dependabot) + lint-level static analysis (**SonarQube CE**); full SAST/DAST and penetration testing are enterprise. **Chaos engineering is enterprise-evolution** (Temporal self-healing covers the resilience basics). Accessibility/compatibility testing follow the Experience deferrals.

**User-Facing Qualities:**
- [ ] Accessibility Testing (WCAG conformance)
- [ ] Compatibility Testing (cross-browser, device, OS)

#### How will we evaluate AI system quality?

**Evaluation Foundations:**
- [ ] Data Contract Validation Gate (schema / semantic / temporal / distributional — MLOps 2.0 CDV)
- [ ] Golden Dataset Construction & Maintenance (industry-specific benchmark covering the production domain)
- [ ] Golden Dataset Coverage & Staleness Audit (is the benchmark still representative of live distribution?)
- [ ] Golden Dataset CI Regression Gate (auto full-benchmark on model / prompt / embedding change; block deploy below baseline)

**Quality & Faithfulness Metrics:**
- [ ] Multi-Dimensional Semantic Evaluation (faithfulness, answer relevancy, context precision)
- [ ] RAG Triad Evaluation (context relevancy, faithfulness ≥ 0.95, answer relevancy ≥ 0.90)
- [ ] Hallucination Rate Measurement & Guardrail Thresholds
- [ ] LLM-as-a-Judge Pipeline Setup

**Safety & Fairness:**
- [ ] Red-Team & Adversarial Testing (prompt injection, jailbreak, data poisoning)
- [ ] Bias & Fairness Scanning
- [ ] Multi-Agent Simulation & Autonomy Boundary Testing (ADLC gate before full implementation)

**Comparison & Economics:**
- [ ] A/B Evaluation between Model Versions & Prompt Versions
- [ ] Token Economics Gate (cost and TTFT within approved budget at peak concurrency)

> **Decision:** AI-quality evaluation is **deliberately deferred** (the AI-domain "再说") — golden datasets, RAG-triad / faithfulness metrics, LLM-as-judge, red-teaming, and A/B eval are a deep topic to settle on their own. Left fully unticked, but **architecture-ready**: the Prompt Registry `test_cases` field + unified event hook are the mount points, so deferral carries no rework cost. Knowing that non-deterministic AI output needs *eval sets*, not traditional assertions, is the design stance here.

### Quality Management

#### How will we automatically enforce code quality?

- [x] Static Analysis & Linting (SonarQube, SpotBugs)
- [x] Code Formatting Enforcement (CI-enforced, not advisory-only)
- [ ] Code Complexity & Maintainability Gates (cyclomatic complexity, duplication thresholds)
- [ ] Technical Debt Tracking & Quality Gate (debt ratio; blocker / critical issue gate)

> **Decision — CI quality gates.** Five **hard gates** (block merge): **build passes (Gradle)**, **tests pass (Spock)**, **license scan** (principle 11), **dependency vulnerability scan** (Trivy/Dependabot), **lint + formatting** = **SonarQube CE** (static analysis + the fixed-domain coverage gate) + **Spotless** (Apache-2.0). **SonarQube CE license note** (a DEPENDENCIES.md informed entry): LGPLv3 core + SSALv1 analyzers (source-available since 2024-11), used *only* as a CI gate scanning our own code — non-competing use, not redistributed, and Sonar data is not fed to AI (SSALv1 clause c); none of the three prohibitions trigger → free + compliant, no stack change. **Coverage threshold applies only to fixed-domain core modules** — no global coverage KPI (it just forces meaningless tests); **the AI domain has no coverage gate** (meaningless for non-deterministic output). Complexity/tech-debt gates are not enforced — maintainability is upheld by principles 4 (complexity budget) + 5 (loose coupling / module boundaries) + the engine-adapter isolation, not by a CI metric. **Engineering metrics = DORA**, a free byproduct of Git + CI (no separate tooling).

#### How will we govern quality standards and knowledge?

- [ ] Quality Assurance & Peer Reviews (code review, design review)
- [ ] Methodology Compliance Management
- [ ] Policies & Standards Management
- [x] Architecture Decision Records (ADRs)
- [x] Knowledge Management & Documentation

> **Decision — ADRs.** Every significant decision is captured as an ADR. **Template = MADR skeleton + a one-line Y-Statement summary at the top** ("In the context of X, facing Y, we chose Z to achieve W, accepting trade-off T") so a reader grabs the core on scan and the structured body (Status / Context / Drivers / Considered Options / Outcome / Consequences / Links) supports depth. **Numbering = sequential `NNNN-short-title.md`; Status = Proposed / Accepted / Deprecated / Superseded by NNNN / Rejected.** A superseded ADR is **never deleted** — its status flips and history is preserved (the same revocation-cascade + history-traceability discipline used in the Data layer, applied to governance). Stored in-repo as Markdown (GitHub-rendered, co-evolves with code, principle 8); each ADR's Links field back-references the corresponding checklist section (bidirectional nav). **Backfill = batch-generate the first round AFTER the checklist is complete** (~16 highest-value ADRs first: AI-Native positioning, Modular-Monolith+Worker-Pool, Approved Primitives, IFC, Federated Sovereignty, Identity Delegation, Workflow-as-Code, Temporal+engine-adapter, gRPC-only API surface (one Armeria port serving gRPC/gRPC-Web/REST, no proxy; Protobuf single-source contract), data-plane+SeaweedFS-over-MinIO, RAG/Memory+Lessons, no-Agent-Framework, Java + Armeria + Spring Boot application stack (runtime/framework/JdbcClient persistence + H-group toolchain Gradle/SonarQube-CE/Spotless/Spock; no-proxy / license / two-fer tradeoff), infrastructure-identity CA delegation (consume-or-bundle EJBCA / two cert planes / PQC / OpenBao=keys-only), Curated Publish Pipeline (IFC egress check at the repo-publish layer — `publish` branch built by a filter script, dry-run diff + egress assertion, `design-journal` never published), model-call layer = LangChain4j (JVM in-process commodity translation layer; avoids duplicating the self-built LLM Gateway + no Python sidecar + same-stack; behind the interface, reversible)) — the checklist Decision notes already hold Context/Drivers/Options/Outcome, so conversion is reformatting + adding the Y-Statement.

> **Decision — Knowledge Management & Documentation.** Three-layer asset model: **(1) durable outward assets** = `README.md` + `docs/architecture.md` + `docs/adr/`; **(2) scaffolding** = this checklist, archived as `docs/design-journal/` — **the checklist is explicitly NOT the final asset** (it is a thinking structure; conclusions are extracted into the durable assets). **README = minimal facade** (one-line positioning + one skeleton Mermaid diagram + three highlight bullets + links into `docs/`). **`architecture.md` = top-down narrative** (positioning → skeleton diagram → three pillars [Approved Primitives / IFC / Federated Sovereignty] → key trade-offs → tech-stack table). **All diagrams = pure Mermaid, version-controlled** (`subgraph` + `classDef` styling; no binary/external images that drift from code). All Markdown, GitHub-native rendering. Trade-off accepted: lower first-glance flashiness than a hero PNG, in exchange for version-controllable, diffable credibility — aligned with the evaluator/tech-lead audience and Automation-First.

---

## 5. Delivery

### Pipeline

#### How will we automate the delivery pipeline?

**Pipeline Automation:**
- [x] Application Build & Image Build Automation
- [x] DevSecOps Automation & Policy Enforcement
- [ ] Continuous Delivery Orchestration
- [x] Pipeline-as-Code (declarative, version-controlled pipeline definitions)

**GitOps Delivery:**
- [ ] GitOps Delivery Model (Push vs. Pull strategy)
- [ ] GitOps Secret Externalization (External Secrets Operator / Sealed Secrets; no plaintext / Base64 secrets in Git)
- [ ] GitOps Configuration Drift Auto-healing Policy (auto-heal stateless; alert + human review for stateful)
- [ ] GitOps Multi-tenant Isolation (Argo CD AppProject whitelist / Flux ServiceAccount impersonation)
- [ ] Break-Glass Emergency Access Procedure (TTL ephemeral tokens + session recording + mandatory post-incident sync back to Git)

> **Decision:** CI build + image automation (locked); **DevSecOps** = license + vulnerability scanning embedded in CI; **Pipeline-as-Code** = version-controlled CI config (principle 8). **The entire GitOps block + CD orchestration are enterprise-deferred** (Platform: K8s/IaC/GitOps reserved but deferred) — CD auto-deploy is N/A at PoC (no service operated). Kept honestly blank.

#### How will we secure the software supply chain?

- [x] SBOM Generation at Build Time (CycloneDX, SPDX)
- [ ] Container Image Signing (Cosign / Sigstore)
- [ ] Signature & Provenance Verification at Deploy (admission-control policy gate before run)
- [x] Artifact Vulnerability Scanning
- [x] Dependency License Compliance Check
- [ ] AI Model Artifact Integrity Verification

> **Decision:** Same decision as Security → supply chain (SBOM + vulnerability scan + license scan, all locked) — ticked here to close the cross-reference. Image signing / SLSA / admission control are enterprise; no AI model artifacts (no training).

### Release Governance

#### How will we control and approve releases?

- [x] Release Approval Gates (automated + human)
- [ ] Environment Promotion Rules
- [ ] Environment Configuration Management (dev / staging / prod parity)
- [ ] Change Freeze / Release Window Policy
- [x] Seed / Bootstrap Asset Approval Workflow (e.g., Prompt approval for AI-native systems)

> **Decision — dual-platform + Curated Publish Pipeline.** Two platforms with distinct roles: **self-hosted GitLab = private workshop** (real dev, full history, all branches, internal CI/CD, WIP, `design-journal`) and **GitHub = public gallery** (curated, finished assets only) — the same scaffolding-vs-asset split as the doc strategy. Branching (GitHub Flow), **Conventional Commits**, tagging, and CI are GitLab-internal workflow (not architecture). **Publishing = a `publish` branch built by a filter script** (GitLab repo = source, `publish` branch = the built artifact, script = the build step — isomorphic to a static-site source→build→deploy). The script's diff is the **only leak gate**, so it runs a **dry-run diff + egress-assertion review before push** (assert `design-journal/` empty, no `.env` / secrets) — the **IFC egress check applied at the repo-publish layer** (worth its own ADR). **`design-journal` is never published** — the GitHub gallery shows only conclusion assets (README + architecture.md + ADR + code). **Versioning = SemVer** (MAJOR.MINOR.PATCH; same source as the API/DSL N-1 + Protobuf evolution rules). **`CHANGELOG.md`** (Keep a Changelog) complements ADRs (ADR = *why*, CHANGELOG = *what changed*); both auto-derivable from Conventional Commits. **Release form = Git tag + GitHub Release (with CHANGELOG) + container images** (Core/Worker — containers are already the deploy vehicle); Helm/Compose deferred to the first enterprise deployment. **CD (auto-deploy to prod) is enterprise-deferred** — no service is operated at PoC. DB migrations (Flyway Community, plain SQL, set in Data) are part of the release.

#### How will we roll out and roll back safely?

- [ ] Deployment Strategy (Blue-Green, Canary, Rolling, Feature Flags)
- [ ] Progressive Delivery & Automated Rollback Triggers (error-budget / SLO-based)
- [ ] Rollback Procedures & Runbooks
- [ ] AI Model Shadow Deployment & A/B Evaluation Gate

> **Decision:** Blue-green / canary / feature-flags / progressive delivery / automated rollback are all **N/A** — no production service is operated (software provider, not operator). Rollback semantics live at the artifact layer = **redeploy the previous SemVer container-image tag** (the deployer's action); **DB-migration rollback** is the migration tool's job (Flyway Community, set in Data). AI model shadow-deploy / A-B ties to the deferred AI-evaluation domain.

### Environments

#### Which environments must we provision across the lifecycle?

**Development & Fix:**
- [x] Development Environments
- [x] Unit / Component Test Environments
- [ ] Fix / Hotfix Development & Test Environments
- [ ] Ephemeral Preview Environments (per-PR / on-demand, auto-torn-down)

**Test & Pre-Production:**
- [x] Prototype & PoC Environments
- [ ] System / Integration Test Environments
- [ ] External Integration (Partner) Test Environments
- [ ] User Acceptance Test (UAT) Environments
- [ ] Performance & Scalability Test Environments
- [ ] Operational Readiness Test (ORT) Environments
- [ ] AI Simulation & Evaluation Environments

**Production:**
- [ ] Live Production Environments
- [ ] Pilot / Canary Environments
- [ ] Disaster Recovery Environments
- [ ] Training Environments

> **Decision:** **`docker compose` brings up the full local dependency stack in one command** (PostgreSQL + Valkey + Qdrant + SeaweedFS + Meilisearch + Temporal) — a must, given the five-component data plane; reuses the "containers as deploy vehicle" decision, and doubles as a quick-start showcase asset (a runnable manifest of the data architecture). **DevContainer optional** (nice-to-have reproducibility). Dev + PoC + CI test environments exist. **Ephemeral preview and AI simulation/eval environments are reserved hooks** (the latter ties to the deferred AI-eval domain). System-integration / UAT / performance / ORT / hotfix and all production-class environments (live / canary / DR / training) are **N/A** — no service is operated.

---

## 6. Operations

### Observability

#### How will we observe system health and performance?

**Telemetry Pillars:**
- [x] Structured Logging (correlation IDs, log levels, audit trail)
- [x] Metrics Collection & Dashboards
- [ ] Distributed Tracing (OpenTelemetry)
- [ ] Trace Context Propagation across Agent / LLM / Tool Boundaries (W3C traceparent / baggage; unified OTel traces-metrics-logs standard)
- [ ] Zero-Overhead / Low-Footprint Telemetry (eBPF / kernel-level capture; avoid runtime logging bloat)

**Health & Detection:**
- [ ] Application Performance Monitoring (APM)
- [ ] Synthetic & Real-User Monitoring (RUM)
- [ ] SLO / Error-Budget Monitoring & Burn-Rate Alerts
- [ ] Alerting & On-Call Routing

> **Decision (principle 7).** PoC observability = **structured logging** (correlation IDs) + **basic metrics & dashboards** (cost / token / latency) + the unified event hook (audit). **OpenTelemetry is the reserved vendor-neutral standard** (principle 11) but **distributed tracing is enterprise-deferred** (Paradigm note). **SLO/error-budget monitoring and on-call routing left unticked** — error budgets are an SRE-of-a-service concept and no service is operated (software provider, not operator); job completion/failure still reaches the user via the notification channels (email/push). APM/RUM/eBPF are enterprise.

#### How will we observe AI and agent behavior?

**LLM Call Layer:**
- [ ] LLM Call Tracing (OTel gen_ai.* Semantic Conventions)
- [x] Token Usage Monitoring (input / output / cached tokens per request)
- [x] Time-to-First-Token (TTFT) & Streaming Latency Tracking
- [ ] TTFT vs. TPOT Separated Monitoring (first-token latency vs. per-token rate; precise fault domain)
- [x] Model Version & Provider Tracking
- [ ] Silent Model Version Drift Tracking (request.model vs. response.model; detect silent downgrade)
- [ ] Token Quota & RPM/TPM Limit Pre-warning (per feature / team / user; prevent single-request quota exhaustion)

**Agent Layer:**
- [ ] Agent Tool-Call Chain Visualization
- [ ] Multi-step Reasoning Trace Capture
- [ ] Agent Goal-Completion & Task-Success Rate
- [ ] Agent Infinite-Loop Protection (single-session retry limits & circuit breakers; detect tool-call retry storms)
- [ ] Multi-step RAG Retrieval Provenance Logging (retrieved doc IDs + similarity scores; chain-level evidence)

**Quality Layer:**
- [ ] Hallucination / Confidence Score Monitoring
- [ ] Hallucination Rate Automated Evaluation (LLM-as-Judge offline sampling; baseline threshold alerting)
- [ ] Guardrail / Safety-Filter Trigger Rate
- [ ] Output Format Compliance Rate
- [ ] Prompt Drift & Quality Regression Detection

**Cost Layer:**
- [ ] Per-Request Cost Attribution (real-time Token cost per feature / team; histogram monitoring)
- [ ] Provider Cache Economics Audit (cache_read vs. cache_creation ratio; guide context truncation)
- [ ] AI Observability Platform Selection (Langfuse, Arize, LangSmith, W&B)

> **Decision:** PoC AI observability = what the LLM Gateway already owns — **token usage, TTFT/streaming latency, model version & provider** (matches the AI-Gateway *AI-Specific Observability* tick). Agent-layer visualization, quality-layer (hallucination/drift) monitoring, per-request cost attribution, and a dedicated AI-observability platform are tied to the deferred AI-evaluation domain / enterprise.

### AI System Operations

#### How will we manage the model, prompt, and embedding lifecycle?

- [ ] Model Drift Detection & Retraining Trigger
- [x] Prompt Lifecycle Operations (approval queue, hot-reload, cache invalidation)
- [x] AI Asset Review Workflow (human approval gate for AI-proposed changes)
- [ ] Embedding Model Version Upgrade Procedures (blue-green re-indexing: shadow index + dual-write + cutover + second-level rollback)
- [ ] Vector Space (Embedding) Drift Continuous Monitoring (detect silent recall degradation; trigger re-embedding)
- [ ] Vector Index Maintenance & Consistency Checks

> **Decision:** **Prompt lifecycle ops = the Prompt Registry state machine** (DRAFT→APPROVED + approval queue). **AI asset review = the Approved-Primitives flow** (AI proposes → human approves → enters Registry) — the core safety model, directly mapped. No model-drift/retraining (prompt-engineering + RAG, no training). Embedding blue-green re-indexing + vector-space drift monitoring + index maintenance are enterprise (Data → vector governance).

#### How will we keep AI services reliable in production?

- [x] AI Readiness Check (required assets in ACTIVE state before accepting requests)
- [x] Model Multiplexing Auto-Failover Validation (zero-downtime cutover to heterogeneous backup model)
- [x] Foundation Model Provider Failover Strategy
- [x] Foundation Model Deprecation & End-of-Life Migration Tracking
- [ ] Independent Evaluator Architecture (physically decoupled from main LLM; continuous factual-consistency monitoring)

> **Decision:** All covered by the routing abstraction + Approved Primitives. **Readiness check** = the DSL structural validation "references must be in APPROVED state". **Model + provider failover** = the graceful-degradation chain (cloud→local→rule) + Model Tag Routing's candidate list (one routing abstraction). **Model sunset/EOL** = Model Tag Routing is naturally immune (a tag matches any available model — Vendor Governance). Independent-evaluator architecture ties to the deferred AI-evaluation domain.

### Infrastructure Operations

#### How will we run day-to-day infrastructure operations?

- [ ] Cloud Management & Brokerage
- [x] Scheduling & Job Orchestration
- [ ] Capacity Planning & Resource Forecasting
- [x] Backup & Restore
- [x] Archiving & Data Retention
- [ ] Chaos Engineering & Continuous Resilience Verification

> **Decision:** **Job scheduling = Temporal's built-in cron/scheduler** (locked). **Backup/restore + archiving/retention = Data-layer decisions** (pg_dump/WAL → SeaweedFS + tiered retention + Tier-1 episodic 90-day window). Cloud management / capacity planning / chaos engineering are N/A or enterprise.

#### How will we manage patching and compliance baselines?

- [ ] OS / Kernel Patch SLA (CVSS High <24h start / <1 week complete; Moderate <1 week / <1 month; compensating controls + exception approval)
- [ ] Kernel Live-Patching (kpatch / Livepatch) & Restart Exception Management (compensating controls + CISO exception approval)
- [ ] Container Base Image Drift Detection (runtime binary drift auto-alert; floating-tag triggers image rebuild pipeline)
- [ ] Middleware EOL Tracking & Rolling Upgrade (LCM toolchain; HA cluster rolling patch parameter tuning)
- [ ] Patch Rollback Capability Validation (LVM / Btrfs snapshot automation; BMC out-of-band emergency takeover)
- [ ] CIS Benchmark Compliance Baseline Scan (CIS-CAT Pro deep verification; OpenSCAP + OVAL automated pipeline)
- [ ] IaC Declarative Guardrails & Zero-Touch State Convergence (Terraform boundary guardrails + Ansible remediation depth)

> **Decision:** OS/kernel patching, live-patching, image-drift detection, middleware EOL, CIS baseline scans, IaC guardrails are all the **deployer's** responsibility (software provider, not operator; the deployer owns the infrastructure) — consistent with the Compliance-Ready positioning. The project side only guarantees that container images are built on actively-maintained OSS (principle 11's continuous license review doubles as an activeness check).

#### How will we operate databases in production?

**Performance & Health:**
- [ ] Slow Query Monitoring (Performance Schema / pg_stat_statements aggregation; query digest auto-alert)
- [ ] Deadlock & Lock Contention Tracking (blocking-chain detection; InnoDB lock tracing & transaction replay)
- [ ] Replication Lag Monitoring (MySQL GTID timestamp / PostgreSQL WAL LSN offset)
- [ ] PostgreSQL Vacuum Health Monitoring (prevent Vacuum saturation / WAL storm silent degradation)
- [ ] Index & Storage Bloat Monitoring
- [ ] Connection Pool Governance (HikariCP sizing + leak detection; PgBouncer transaction-level multiplexing)

**Change & Resilience:**
- [ ] Online Schema Change Rolling Upgrade (pt-osc trigger-based / gh-ost binlog-decoupled; avoid global MDL lock)
- [ ] Point-in-Time Recovery (PITR) & Restore Drills
- [ ] Database-Layer Data Quality Assertions (periodic offline business data quality validation)

> **Decision:** Slow-query / deadlock / replication-lag / vacuum / bloat / connection-pool / online-schema-change / PITR drills are all the **deployer's production DB operations**. The project side delivers: **Flyway (plain SQL) migration scripts**, a thin **JdbcClient data-access layer**, and sound index design. PITR is enterprise (Data).

### FinOps & Cost Management

#### How will we attribute and track costs?

**Cost Visibility & Tagging:**
- [x] Unit Economics & Cost-per-Transaction Tracking (cost per request / active user)
- [ ] Resource Tagging & Allocation Strategy (untagged / orphaned resource detection)
- [ ] Cloud Cost Anomaly Detection & Budget Alerts
- [ ] FOCUS v1.0 Unified Billing Spec (cross-cloud billing standardization; eliminate reconciliation friction)
- [ ] Chargeback / Showback Model (allocate costs to business units or tenants)

**AI / Token Cost Attribution:**
- [x] AI API Cost Attribution (per feature / team / user)
- [x] LLM Request-Level Three-Dimension Tagging (Environment / Project / Workload; unit economics prerequisite)
- [ ] Agentic Context Window Creep Monitoring (request-depth tracking + per-session Token budget circuit-breaker)
- [x] Token Budget Enforcement & Alerting

> **Decision — Token as the Universal Cost Currency.** This is the home of the only mandatory SLO (cost visibility) and it quantifies the core value proposition, so it is built. **All execution backends share `token` (input + output) as the common unit** — cloud, local (Ollama also reports tokens), and manual (pasted text → tokens) are all token-measurable, making the **gear-shift three-way comparison natively comparable**. **Money cost is an optional overlay column, lit up only for billed backends** (cloud = tokens × provider price); local and manual are **not** assigned a fabricated money figure (manual additionally records intervention time + count, since it trades away automation). **Aggregation: per-step → per-run → per-backend** (per-step tokens sum to per-run; per-run groups by backend for the comparison). Data source = the LLM Gateway's per-call record. **Context-creep / Denial-of-Wallet circuit-breaker is a reserved hook, not built at PoC** — a workflow-level optional DSL field `max_total_tokens` (reusing the step-level opt-in constraint mechanism); enforcement/alerting is enterprise (the real risk is unattended scheduled jobs). Zero rework: token metering is already collected, so the breaker is just "read the number, compare a threshold." Cloud-billing tagging / anomaly / FOCUS / chargeback are N/A (no cloud operated).

#### How will we optimize cost?

**Compute & GPU Efficiency:**
- [ ] General Compute Right-Sizing & Idle Resource Reclamation
- [ ] Autoscaling & Scheduled Scaling Policy (scale-to-demand)
- [ ] GPU / Accelerator Utilization Monitoring (Tensor Core Duty Cycle; not just CPU/RAM)
- [ ] MIG Multi-Instance GPU Time-Sharing (inference workload bin-packing)
- [ ] Non-Production GPU Cluster Auto-Scale to Zero (off-peak shutdown; LoRA/QLoRA reduces VRAM demand)
- [ ] Spot / Reserved Instance Strategy for AI Workloads
- [ ] Inference Capacity Combination Strategy (base-load PTU + async batch Spot; autoscale on queue depth)
- [x] Inference Model Right-Sizing (route low-complexity to SLM / distilled; reserve top-tier for high-value reasoning)
- [ ] Compute vs. Cache Trade-off Analysis (semantic caching ROI tracking)

> **Decision:** **Inference right-sizing IS the gear-shift mechanism itself** (tag routing sends low-complexity steps to local SLM, reserves frontier models for high-value reasoning) — already locked, no new work. Semantic-caching ROI tracking follows the semantic-cache deferral (enterprise). All GPU/accelerator/spot/scale-to-zero and vector-storage cost items are N/A or the deployer's concern (no GPU cluster operated).

**Storage & Data Movement:**
- [ ] Network Egress & CDN Cost Optimization
- [ ] Vector Database Break-Even Analysis (Serverless vs. Dedicated switch at scale threshold)
- [ ] Vector Index Quantization Compression (scalar / product quantization 4× memory reduction; separate metadata)
- [ ] Vector Database Storage & Retrieval Cost Monitoring
- [ ] Data Gravity Management (co-locate training data with GPU clusters; prevent cross-region transfer cost overruns)
- [ ] Compliance Retention Data Intelligent Storage Tiering (low-frequency inference logs to cold storage)

#### How will we govern FinOps practice and accountability?

- [ ] FinOps Operating Model & Practice Maturity (Crawl / Walk / Run; cross-functional FinOps team)
- [ ] Cost Accountability & Budget Ownership per Team
- [ ] AI Investment Council Agile Governance (FinOps + Product + Finance + Engineering + Compliance; incremental funding + unit economics gate)
- [ ] Energy / Carbon Cost Reporting per Workload (ties to Strategy sustainability obligations)

> **Decision:** FinOps *governance* (operating model, per-team budget ownership, investment council) is **N/A** — solo + no service operated. Energy/carbon reporting is handled in Strategy → Sustainability.

### Service Management

#### How will we manage IT services and service levels?

- [ ] IT Service Catalog Management
- [ ] Service Request & Fulfillment Management
- [ ] Business Service Level Management (BSM)
- [ ] IT Service Level Management (ITSM)
- [ ] Metering & Billing
- [ ] Capacity Management

> **Decision:** Entire section **N/A** — software provider, not service operator; no operated service means no service-level management (catalog / fulfillment / BSM / ITSM / metering-billing / capacity).

#### How will we build SRE practice and reliability culture?

- [ ] Architecture Decision Record (ADR) Human Review (mandatory pre-merge review of context, trade-offs, rejected alternatives)
- [ ] Chaos Engineering & Incident Commander Drills (Game Days; IC role rotation; high-pressure response training)
- [ ] Blameless Post-Mortem Facilitation & Closed-Loop Accountability (human-facilitated; sprint-bounded fix tracking)
- [ ] Error Budget Cross-Departmental Governance (leadership defends SRE discipline against feature pressure)
- [ ] Tacit Knowledge Transfer (Incident Swarming paired debugging; prevent SRE attrition brain drain)

> **Decision:** SRE practice (Game Days / blameless post-mortems / error-budget governance / tacit-knowledge transfer) is **N/A** — solo + no service operated. One note: **ADR itself is already the decision-review mechanism** — MADR's Considered-Options / Consequences fields force recording trade-offs and rejected alternatives, i.e. the solo-equivalent of "ADR human review".

### Incident Management

#### How will we respond to and learn from incidents?

- [ ] Incident Classification & Severity Levels (traditional + AI-specific: hallucination, prompt injection, model drift, agent runaway, Denial-of-Wallet)
- [ ] On-Call Rotation & Runbooks
- [ ] Incident Response Coordination & Communication (status page, stakeholder comms)
- [ ] Problem Management & Root Cause Analysis
- [ ] Post-Incident Review (PIR)

> **Decision:** Severity tiers / on-call / status page / RCA / PIR are **N/A** — no service operated, so no production incident response. Unattended scheduled-job failures reach the user via the notification channels (email/push).

#### How will we handle AI-specific incidents?

- [ ] AI Incident Response & SLA (security P1 <15 min; hallucination deviation <4 h remediation; drift isolation <1 h)
- [x] Agent Kill Switch & Circuit Breaker Validation (<1 s blocking of all outbound requests on compromise)
- [x] Agent State Rollback Viability (state-modifying ops have reverse snapshots; pre-compromise state restorable)
- [ ] AI-Specific Forensic Evidence Preservation (raw prompt + RAG chunks + model params; structured write to SIEM)
- [ ] Data Feedback Loop Sanitization (prevent drift outputs re-entering training corpus; avoids self-poisoning)
- [ ] Threat Hypothesis Sandbox (periodic adversarial injection in isolated env with sanitized prod data)
- [ ] Model Decommissioning Change Management (NIST AI RMF Manage 4.1; staged replacement + secure decommissioning)
- [ ] AI Monitoring Pipeline Uptime SLA (regulated-industry pipeline SLA ≥99.95%)
- [ ] EU AI Act Art. 73 Compliance Reporting (serious incidents: standard ≤15 days, critical ≤2 days notification)

> **Decision:** **Kill switch = Temporal cancel + revoking a Registry entry cascades to stop all referencing workflows** (locked). **State rollback = Temporal checkpoint/durable execution + idempotency** (restore to the pre-compromise checkpoint). Other AI-incident SLA / forensics / EU AI Act reporting are deployer/enterprise. Worth noting: **feedback-loop self-poisoning is structurally prevented** — Lessons/Episodic memory enters only via human approval (Approved Primitives), so drift outputs can't silently re-enter the corpus.

### Change Management

#### How will we control changes safely?

- [ ] Change Request (CR) & Review Process
- [ ] Change Advisory & Approval Workflow (standard / normal / emergency change types)
- [x] AI Asset Change Control (prompt, model, embedding, strategy-document changes)
- [x] Backup Schedule & Retention Period
- [ ] CMDB Logical Boundary Human Audit (periodic asset-to-cost-center mapping review; high-security workload VPC boundary verification)

> **Decision:** **AI asset change control = the Approved-Primitives discipline** — any change to a prompt / tool / MCP / model / workflow / Lessons entry is versioned and re-approved (the Registry snapshot + re-approval-on-change model). **Backup & retention** is set in the Data layer (backup to SeaweedFS + tiered retention). Human CR/advisory boards are N/A (solo); release-time change control lives in the publish pipeline below.

### SLA & Operational Commitments

#### What operational commitments must we guarantee?

- [ ] SLA Commitments (availability, latency, accuracy)
- [x] AI-Specific SLA (response quality floor, graceful degradation policy)
- [ ] RTO / RPO Targets per Service Tier
- [ ] Capacity Thresholds & Scaling Triggers
- [ ] Business Continuity & Disaster Recovery (BCP / DR) Procedures

> **Decision:** No service operated → no availability / RTO / RPO / capacity / BCP-DR commitments (enterprise-deferred, per Strategy SLO). The one commitment-shaped item — **AI quality floor + graceful-degradation policy** — exists as **step-level opt-in constraints** (quality floor) + the **cloud→local→rule degradation chain**, not as a global SLA. The cost SLO (soul metric) lives in FinOps.

---

## 7. Governance

### Data Governance

#### How will we govern data across its lifecycle?

**Classification & Lifecycle:**
- [x] Data Classification Policy
- [x] Data Retention & Archiving Rules
- [ ] Data Ownership & Stewardship (owners, stewards, RACI)
- [ ] Data Catalog & Metadata Management
- [ ] Data Quality Standards & Monitoring

**Privacy & Residency:**
- [ ] PII / PHI Handling Rules
- [ ] Consent & Purpose-of-Use Management
- [ ] Cross-Border Data Transfer Restrictions
- [x] Data Sovereignty & Residency Policy

> **Decision:** **Classification = IFC three-level sensitivity tags** (public / internal / confidential). **Retention/archiving = the Data-layer tiered retention** (Tier-1 episodic 90d / Registry permanent). **Data sovereignty = local-first + pure self-host + federated per-instance autonomy** (naturally satisfies residency). PII handling / consent / cross-border are the **deployer's** policy configured against the IFC labels (Compliance-Ready: the project provides the mechanism, not the policy). The **Registry is itself the data catalog** — no separate enterprise catalog platform; ownership is implicit (solo); data-quality monitoring is enterprise.

#### How will we govern AI training and inference data?

**Training Data:**
- [ ] AI Training Data Compliance (copyright, consent, bias audit)
- [ ] Training Data Scraping Compliance Audit (verify robots.txt / opt-out compliance; remove pirated or unauthorized content)
- [ ] Training Data Bias Audit (ISO/IEC 5259-2; 14-dimension quantification incl. representativeness & coverage)
- [ ] GPAI Training Content Copyright Transparency Summary (EU AI Act Art. 53; Fair Use analysis for disputed corpora)

**Inference Data:**
- [x] AI Inference Data Logging & Retention Policy
- [ ] Pre-Tokenization PII Masking (dynamic scrubbing at the AI Gateway layer before tokenization; do not rely on the LLM to self-filter)
- [ ] Cross-Border High-Sensitivity Call Geo-fence Routing (auto-downgrade to on-premises compliant model on restricted cross-border detection)
- [ ] AI Log Non-Repudiation (EU AI Act Art. 11; full-chain logs; <72h root-cause traceability)

> **Decision:** **Inference logging = prompt/completion call records + the unified event hook** (retention follows the Data-layer policy). **All training-data governance is N/A** (prompt-engineering + RAG, no training, no corpus). Cross-border high-sensitivity routing: the **IFC egress check already provides the structural mechanism** (confidential cannot leave to untrusted) — concrete geo-fence policy is the deployer's config. Pre-tokenization PII masking is enterprise (AI Gateway); log non-repudiation is enterprise/deployer.

#### How will we govern synthetic data and model IP?

**Synthetic Data:**
- [ ] Synthetic Data Governance & Isolation Policy
- [ ] Synthetic Data Validation Trinity (Fidelity + Utility + Privacy balanced quantification; boundary fragility testing)
- [ ] Synthetic Data Source Certification & Physical Isolation (org-certified seed data; prevent hidden production-pool contamination)
- [ ] Synthetic Data C2PA Provenance Marking (EU AI Act Art. 50; human-machine interaction transparency)

**Model IP & Licensing:**
- [ ] Fine-Tuned Model License Propagation Audit (LoRA + Copyleft ownership risk; commercial-license whitelist)
- [x] OSS Model Open-Washing Identification (scrutinize EULA commercial restrictions; systemic-risk models subject to mandatory data audit)

> **Decision:** **OSS model open-washing identification is already part of principle 11** (verify model-weight licenses are genuinely open / commercial-OK, avoid EULA restrictions). **No synthetic data** (not used at PoC). **No fine-tuning → no LoRA/copyleft license-propagation risk** (prompt-engineering + RAG only).

### AI Governance

#### Which AI regulatory frameworks must we comply with?

- [ ] EU AI Act Compliance Checklist (High-Risk: conformity assessment, human oversight)
- [ ] EU AI Act Conformity Assessment Dual-Track Trigger (design: self-/third-party assessment; pre-deployment: technical documentation + post-market monitoring)
- [ ] NIST AI RMF Alignment (GOVERN / MAP / MEASURE / MANAGE)
- [ ] NIST AI RMF SDLC Control-Point Mapping (MAP → requirements/architecture; MEASURE → testing; MANAGE → operations)
- [ ] ISO/IEC 42001 AI Management System (beyond ISO 27001: lifecycle, bias audit, continuous learning, human oversight)
- [ ] AI TRiSM Framework (Trust, Risk, Security Management)
- [ ] APAC AI Governance Alignment (Japan METI AI Guidelines for Business; regional rules beyond EU/US)

> **Decision:** EU AI Act / NIST AI RMF / ISO 42001 / AI TRiSM / APAC alignment all belong to the **deployer** — software provider, not operator; the compliance subject is the deploying organization (it is the data controller and carries classification/compliance per its use case — Strategy → Compliance). The project provides **Compliance-Ready mount points** (unified audit event hook; output-layer metadata fields such as AI-generated labeling) so the deployer can plug in without rework, but owns no specific framework compliance.

#### How will we operationalize responsible AI?

- [x] Responsible AI Principles (fairness, transparency, accountability, safety)
- [x] Human Oversight & Override Mechanisms
- [x] Model Cards & Technical Documentation Requirements
- [ ] AI Incident Classification & Response Policy (risk tiering defined in Strategy; incident handling in Phase 6)

> **Decision:** **Human oversight/override = Approved Primitives + HITL approval gates + step-level opt-in constraints** (the core safety model — human approval is the only gate through which the system grows). **Responsible-AI principles = the three pillars** structurally deliver transparency/accountability/safety (Approved Primitives = what AI may use; IFC = where data may go; Federated Sovereignty = each unit autonomous). **Model Cards = Model Registry entry metadata** (trust tags + capability tags + approval record) carries them naturally. Fairness/bias auditing belongs to the deployer (the project trains no models). AI incident policy ties to Operations (mostly deployer).

### API & Integration Standards

#### What API and integration standards must we enforce?

- [x] API Design Standards (REST / gRPC / GraphQL conventions)
- [x] API Versioning & Deprecation Policy
- [x] Message Schema Standards
- [x] Event Contract Versioning
- [x] MCP Tool Definition Standards

> **Decision:** Governance restatement of the Integration decisions. **API standard = gRPC-only + Protobuf single-source-of-truth**. **Versioning = N-1 compatibility + Protobuf evolution rules**. **Message schema = Protobuf** (buf-toolchain managed). **Event-contract versioning = the same Protobuf evolution.** **MCP tool-definition standard = Server registration + Tool snapshot + strict per-call schema comparison** (the three-layer MCP safety). No new decisions — this is the policy view of the Integration tech decisions.

### Security Policy

#### What security policies must we mandate?

- [x] Access Control Rules
- [x] Encryption Requirements (algorithms, key rotation schedule)
- [x] Secrets Management Policy
- [ ] Compliance Requirements (GDPR, SOX, PCI-DSS, HIPAA, DORA, APPI, FISC)
- [ ] Compliance Validation & Enforcement
- [ ] Penetration Testing Cadence

> **Decision:** Governance restatement of the Security decisions. **Access control = Identity Delegation (delegate to IdP) + per-agent least-privilege + Approved Primitives + IFC egress**. **Encryption = TLS/mTLS in transit + at-rest the deployer's + secrets via OpenBao** (**key/secret rotation = OpenBao**; **certificate issuance/rotation = consumed from an external CA** — enterprise plugs in its CA, personal uses bundled EJBCA — *not* OpenBao PKI). **Secrets policy = the pluggable SecretsProvider** (env/SOPS → OpenBao). Compliance frameworks (GDPR etc.) belong to the deployer (Compliance-Ready); penetration testing is enterprise.
