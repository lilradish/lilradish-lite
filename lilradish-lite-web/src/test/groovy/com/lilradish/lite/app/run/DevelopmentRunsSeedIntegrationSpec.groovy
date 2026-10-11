package com.lilradish.lite.app.run

import com.lilradish.lite.app.codestep.CodeSteps
import com.lilradish.lite.app.codestep.ReadTextFile
import com.lilradish.lite.app.codestep.development.StampReference
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.inference.ToolModelCalls
import com.lilradish.lite.app.inference.development.DevelopmentCallConfiguration
import com.lilradish.lite.app.inference.development.DevelopmentModelCalls
import com.lilradish.lite.app.inference.development.DevelopmentSearchConversion
import com.lilradish.lite.app.inference.development.DevelopmentToolModelCalls
import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.app.search.development.DevelopmentSearchWays
import com.lilradish.lite.domain.declaration.Asking
import com.lilradish.lite.domain.declaration.Demand
import com.lilradish.lite.domain.declaration.FieldKind
import com.lilradish.lite.domain.declaration.FieldShape
import com.lilradish.lite.domain.declaration.TextLines
import com.lilradish.lite.domain.filling.FillField
import com.lilradish.lite.domain.filling.FilledFields
import com.lilradish.lite.domain.filling.Filling
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.inference.Envelope
import com.lilradish.lite.domain.model.CameBackMeasure
import com.lilradish.lite.domain.model.ModelCatalog
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.model.SentText
import com.lilradish.lite.domain.registry.EntryVersionId
import com.lilradish.lite.domain.run.EngineActs
import com.lilradish.lite.domain.run.ProductionId
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.RunPayloads
import com.lilradish.lite.domain.run.StepInputs
import com.lilradish.lite.domain.run.StepPositions
import com.lilradish.lite.domain.run.StepRuns
import com.lilradish.lite.domain.run.WorkflowStepId
import com.lilradish.lite.domain.wire.CanonicalJson
import com.lilradish.lite.domain.wire.JsonValue
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.DevelopmentSeed
import com.lilradish.lite.testutil.inference.Json
import com.lilradish.lite.testutil.library.LibraryStore
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * The development seed's runs read as the application reads them and with the engine started on them: where a run is
 * is stored nowhere, so only reading it proves the seed puts it there.
 */
class DevelopmentRunsSeedIntegrationSpec extends Specification {

    static final GroupId SUPPORT = new GroupId(UUID.fromString("00000002-0000-4000-8000-000000000101"))

    static final UserId GRACE = new UserId("000102")

    static final CodeSteps RELEASE = new CodeSteps([new StampReference(), new ReadTextFile()])

    // DB-SPECIFIC: text is a PostgreSQL target type.
    static final String RUN_ROWS = """
            select cast(run.root_run_id as text) as root, '%1\$s' as kind, count(*) as held
              from %1\$s held join runs run on run.run_id = held.run_id
             group by run.root_run_id"""

    /** Each run by number: its state and step as the run page says them, and the position of each of its steps. */
    static final Map<Integer, String> SEEDED = [
        1 : "RUNNING summarise [Running, NotStarted]",
        2 : "DONE - [Done, Done]",
        3 : "DONE - [Done]",
        4 : "DONE - [Done]",
        5 : "FAILED - [Failed]",
        6 : "STOPPED - [AwaitingReview, NotStarted]",
        7 : "RUNNING summarise [Owed, NotStarted]",
        8 : "RUNNING route_by_category [Done, Beneath]",
        9 : "RUNNING summarise_for_escalation [Owed]",
        10: "DONE - [Done]",
        11: "RUNNING summarise_for_escalation [Owed]",
        12: "RUNNING summarise [AwaitingReview, NotStarted]",
        13: "STOPPED - [Done, Running]",
        14: "RUNNING research [Done, AwaitingReview]",
        15: "DONE - [Done, Done]",
        16: "FAILED - [Failed]",
        17: "DONE - [Done, Done, Done]",
        18: "DONE - [Done]",
    ]

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.isolated()

    @Shared
    ModelCatalog catalog

    @Shared
    SearchWays ways

    @Shared
    ToolModelCalls tools

    @Shared
    int databasesMade = 0

    LibraryStore store

    EngineWiring wiring

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
        DevelopmentSeed.catalogBinding()
                .withPropertyValues('spring.profiles.active=local')
                .withUserConfiguration(DevelopmentCallConfiguration, DevelopmentSearchWays, SearchWays)
                .withBean(DevelopmentSearchConversion)
                .withBean(DevelopmentToolModelCalls)
                .run { context ->
                    assert context.startupFailure == null
                    catalog = context.getBean(ModelCatalog)
                    ways = context.getBean(SearchWays)
                    tools = context.getBean(ToolModelCalls)
                }
    }

    def setup() {
        store = seeded("development_runs_" + (++databasesMade))
        wiring = new EngineWiring(store, new DevelopmentModelCalls(), RELEASE, catalog, RELEASE,
                Deployments.DEEP_ENOUGH, EngineWiring.fileDeployment(), ways, tools)
    }

    def "the development seed has real workflow steps and one search pinned to a question with an ordinary topic binding"() {
        expect:
        store.count('select count(*) from workflow_steps') > 0
        // DB-SPECIFIC: PostgreSQL step_kind and entry_kind enum labels select the search and its pinned question below.
        store.count("select count(*) from workflow_steps where kind = 'search'") == 1
        store.count("""select count(*) from workflow_steps step
                join question_versions question on question.entry_version_id = step.pinned_version_id
                where step.kind = 'search' and step.pinned_kind = 'question'
                and question.entry_kind = 'question' and question.search_way = step.search_way""") == 1
        store.texts("""select binding.target_path || ':' || binding.source_path from bindings binding
                join workflow_steps step using (workflow_step_id) where step.kind = 'search'""") as Set == ['topic:topic', 'text:text'] as Set
    }

    def cleanup() {
        wiring?.close()
    }

    def "the seeded file research run reads its natural starter name through the run page reader"() {
        when:
        def read = new Runs(store.session, new GroupRoles(store.session), store.transactionManager(), wiring.snapshots)
                .run(SUPPORT, numbered()[13], GRACE)

        then:
        read.startedBy().userId() == GRACE
        read.startedBy().displayName().value() == 'Grace Starling'
    }

    def "Handle a complaint takes one line, several lines, a number and a term pinned to Complaint categories"() {
        when:
        def workflow = store.transactions().execute {
            wiring.snapshots.asRead(SUPPORT, numbered()[1]).workflow()
        }
        def categories = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000106"))

        then:
        workflow.takes().fields().collect { [it.name().value(), it.shape(), it.demand()] } == [
            ["complaint", new FieldShape.Text(4000, TextLines.SEVERAL), new Demand.Given(true)],
            ["title", new FieldShape.Text(128, TextLines.ONE), new Demand.Given(false)],
            ["amount", new FieldShape.Plain(FieldKind.NUMBER), new Demand.Given(false)],
            ["category", new FieldShape.Term(categories), new Demand.Given(false)],
        ]
        workflow.lists().keySet() == [categories] as Set
        workflow.lists()[categories].terms().collect { it.term().value() } == ["Billing", "Delivery", "Product fault"]
    }

    def "every top-level run holds distinct real inputs fitting only its declared fields"() {
        when:
        def runs = store.transactions().execute {
            numbered().collectEntries { number, run -> [(number): wiring.snapshots.asRead(SUPPORT, run)] }
        }.findAll { number, run -> number <= 12 && run.startedWith() != null }
        def inputs = runs.collectEntries { number, run -> [(number): namedValues(run.startedWith())] }

        then:
        inputs.keySet() == [1, 2, 4, 5, 6, 7, 8, 10, 11, 12] as Set
        inputs.values().collect { it.complaint.value() }.toSet().size() == 10
        inputs.values().every { it.complaint instanceof JsonValue.JsonString && it.complaint.value().contains("\n") }
        inputs.findAll { number, values -> values.containsKey("title") }.collectEntries { number, values ->
            [(number): [values.title.value(), values.amount.value(), values.category.value()]]
        } == [
            1: ["Kettle arrived broken", 39.95, "Delivery"],
            2: ["Charged twice for one order", 89.50, "Billing"],
            6: ["Parcel left in the rain", 124.00, "Delivery"],
            7: ["Order arrived late", 28.75, "Delivery"],
            8: ["Old address on the invoice", 46.20, "Billing"],
            12: ["Refund to the wrong card", 72.90, "Billing"],
        ]
        runs.every { number, run ->
            def fields = FillField.of(run.workflow().takes().fields(), run.workflow().lists())
            def filled = Filling.of(fields, Filling.wire(fields, run.startedWith()))
            inputs[number].keySet() == run.workflow().takes().fields().collect { it.name().value() }.toSet() &&
                    filled instanceof FilledFields && namedValues(filled.values()) == inputs[number]
        }
        inputs.findAll { number, values -> values.containsKey("title") }.values().every {
            it.title instanceof JsonValue.JsonString && it.amount instanceof JsonValue.JsonNumber &&
                    it.category instanceof JsonValue.JsonString
        }
    }

    def "the two child runs inherit different complaints from their own parents without top-level input copies"() {
        when:
        def children = store.transactions().execute {
            [3, 9].collectEntries { number ->
                def at = wiring.snapshots.inTreeAsRead(SUPPORT, numbered()[number])
                def step = at.run().steps().first()
                def question = step.planned().runs() as StepRuns.Question
                def inputs = StepInputs.filled(question.takes(), StepInputs.took(at, step, step.tries().first()))
                [(number): [parent: at.above().run().run(), startedWith: at.run().startedWith(),
                            inputs: namedValues(inputs), parentInputs: namedValues(at.above().run().startedWith())]]
            }
        }

        then:
        children[3].parent == numbered()[2]
        children[9].parent == numbered()[8]
        children.values().every {
            it.startedWith == null && it.inputs == [complaint: it.parentInputs.complaint,
                                                   channel: new JsonValue.JsonString("escalation")]
        }
        children[3].inputs.complaint != children[9].inputs.complaint
    }

    def "every seeded run reads where the seed put it, and no run the seed does not name is there"() {
        when:
        def read = whereEachRunIs()

        then:
        read == SEEDED
    }

    def "only run 1 has an act the engine would make by itself, and that act is to start its first try"() {
        when:
        def acts = nextActs()

        then:
        acts == SEEDED.keySet().collectEntries { [(it): it == 1 ? "StartTry" : "Nothing"] }
    }

    def "every seeded run's page and each of its steps' pages read for a member of its group"() {
        when:
        def pages = stepPagesRead()

        then: "each run's own list of steps names the steps whose pages were read, in their order"
        pages.listed == pages.paged

        and: "every step of every run was read"
        pages.paged == ["1 summarise", "1 route_by_category", "2 summarise", "2 route_by_category",
                  "3 summarise_for_escalation", "4 stamp", "5 stamp", "6 summarise", "6 route_by_category",
                  "7 summarise", "7 route_by_category", "8 summarise", "8 route_by_category",
                  "9 summarise_for_escalation", "10 summarise_for_escalation", "11 summarise_for_escalation",
                  "12 summarise", "12 route_by_category", "13 read_files", "13 research", "14 read_files", "14 research", "15 read_files", "15 research", "16 review_note",
                  "17 read_complaint", "17 stamp", "17 escalate", "18 summarise_for_escalation"]
    }

    def "starting the engine takes run 1 to waiting on review and writes nothing under any other run"() {
        given:
        def before = rowsByRoot()
        def otherRoots = (SEEDED.keySet().collect { root(it) } as Set) - root(1)

        when:
        wiring.started()
        wiring.drained()

        then: "run 1 has tried its first step once, a model producing it, and its values wait on a person"
        whereEachRunIs() == SEEDED + [1: "RUNNING summarise [AwaitingReview, NotStarted]"]
        rowsByRoot()[root(1)] == [run_steps: 1L, productions: 1L, run_step_send_attempts: 1L, model_calls: 1L,
                                  production_inputs: 2L]

        and: "every other tree holds exactly what it was seeded with"
        def after = rowsByRoot()
        after.keySet() == before.keySet()
        after.subMap(otherRoots) == before.subMap(otherRoots)
        !otherRoots.isEmpty()

        and: "nothing was left out, and nothing waits to be settled at the next start"
        store.session.sql("select count(*) from model_calls where outcome is null").query(Long).single() == 0L
        // DB-SPECIFIC: PostgreSQL step_producer enum labels distinguish productions made by a person.
        store.session.sql("select count(*) from productions where ended_at is null and producer <> 'person'")
                .query(Long).single() == 0L
    }

    /** What a call sent and counted remains evidence of the envelope it was sent under. */
    def "historical calls retain their recorded envelope and counts while a new call uses the current engine"() {
        given:
        def historical = historicalCalls()
        def before = callsAsStored()
        assert before.subMap([2, 6, 7, 8, 12]) == historical
        assert before.keySet().containsAll([13, 14, 15])
        assert !before.containsKey(1)

        when:
        wiring.started()
        wiring.drained()

        then:
        def stored = callsAsStored()
        stored.keySet() == before.keySet() + [1] as Set
        stored.subMap([2, 6, 7, 8, 12]) == historical
        stored.subMap(before.keySet()) == before
        stored.subMap([1]) == callsAsTheEngineWritesThem([1] as Set)
    }

    def "search acceptance values retain all Markdown forms and deep text alongside raw declined evidence"() {
        // DB-SPECIFIC: bodies and deep read stored PostgreSQL JSONB value.value columns with String mappers.
        when:
        def bodies = store.session.sql("""
                select value.value from production_values value
                  join productions production on production.production_id = value.production_id
                  join runs run on run.run_id = production.run_id
                 where run.number in (14, 15) and value.field_name = 'body' order by run.number
                """).query(String).list()
        def calls = store.session.sql("""
                select call.answer, call.declined_words from model_calls call
                  join runs run on run.run_id = call.run_id where run.number = 13
                """).query { result, row -> [result.getString(1), result.getString(2)] }.single()
        def deep = store.session.sql("""
                select value.value from production_values value
                  join productions production on production.production_id = value.production_id
                  join runs run on run.run_id = production.run_id
                 where run.number = 14 and value.field_name = 'references'
                """).query(String).single()

        then:
        bodies.size() == 2
        bodies[0] == bodies[1]
        def body = com.lilradish.lite.app.inference.EndpointJson.READER.readTree(bodies[0]).stringValue()
        body.contains('## Imaginary Mooncrate parcel review')
        body.contains('| Evidence | Invented allowance |')
        body.contains('Extra note: include the box label')
        body.contains('```fictional-policy')
        body.contains('[fictional-terms]:')
        body.contains('<aside>')
        body.contains('Evidence&#8203;status')
        body.contains(Character.toString(0x200B))
        !body.contains('48 hours')
        calls[0].startsWith('{"refusal":')
        calls[1].contains('## Missing evidence')
        calls[1].contains('**invented parcel claim**')
        calls[0] != calls[1]
        com.lilradish.lite.app.inference.EndpointJson.READER.readTree(deep).stringValue().startsWith('> ' * 33)
    }

    def "a person's single-line and multiline notes read with their real model reviews in the same declared field"() {
        given:
        def reader = new RunSteps(store.session, new GroupRoles(store.session), wiring.snapshots,
                store.transactionManager(), Deployments.DEEP_ENOUGH,
                new com.lilradish.lite.app.currency.GroupCurrencies(store.session,
                        new org.springframework.transaction.support.TransactionTemplate(store.transactionManager()),
                        new GroupRoles(store.session), com.lilradish.lite.testutil.library.DeployedModels.HELD), com.lilradish.lite.testutil.library.DeployedModels.HELD)
        def step = new WorkflowStepId(UUID.fromString("0000000c-0000-4000-8000-000000000106"))

        when:
        def page = reader.step(SUPPORT, numbered()[16], step, GRACE)
        def tries = page.triesMade()
        // DB-SPECIFIC: text is a PostgreSQL target type; model_call_purpose enum labels select
        // the review call and its sent payload in the two queries below.
        def originals = store.session.sql("""
                select cast(call.model_call_id as text), cast(call.production_id as text), call.answer
                  from model_calls call join runs run on run.run_id = call.run_id
                 where run.number = 16 and call.purpose = 'review' order by call.created_at
                """).query { result, row -> [result.getString(1), result.getString(2), result.getString(3)] }.list()
        def sentPayloads = store.session.sql("""
                select sent.payload from run_step_send_attempts sent join runs run on run.run_id = sent.run_id
                 where run.number = 16 and sent.purpose = 'review' order by sent.created_at
                """).query { result, row -> result.getString(1) }.list()
        def requested = store.transactions().execute {
            def at = wiring.snapshots.inTreeAsRead(SUPPORT, numbered()[16])
            def question = at.run().steps().first().planned().runs() as StepRuns.Question
            [Asking.of(question.instruction(), question.takes(), question.gives(), question.lists()),
                namedValues(at.run().startedWith())["complaint"]]
        }

        then:
        page.run().state() == "failed"
        page.step().name() == "review_note"
        page.declarations()["00000007-0000-4000-8000-000000000111"].gives().first().lines() == "several"
        tries.collect { it.number() } == [1, 2]
        tries.every {
            it.producedBy().kind() == "person" && it.producedBy().person().userId() == "000103" &&
                    it.review().asked() && it.review().by().kind() == "model" &&
                    it.review().by().model() == "sample_model" && it.ended() == "refused_on_review"
        }
        tries[0].values().first().value().stringValue() == "**Imaginary settlement: receipt missing**"
        tries[1].values().first().value().stringValue().startsWith("## Imaginary Mooncrate settlement\n")
        tries[1].values().first().value().stringValue().contains("- **Keep** the payment trace.")
        tries[0].values().first().decision().why() == "**Missing signed receipt.**"
        tries[1].values().first().decision().why().contains("## Missing payment proof\n")
        tries.every { it.values().first().decision().whyLines() == "several" }
        originals.size() == 2
        originals[0].take(2) == ["00000015-0000-4000-8000-000000001601", "00000011-0000-4000-8000-000000001601"]
        originals[1].take(2) == ["00000015-0000-4000-8000-000000001602", "00000011-0000-4000-8000-000000001602"]
        originals.withIndex().every { original, at ->
            def answer = com.lilradish.lite.app.inference.EndpointJson.READER.readTree(original[2])
            answer.path("decisions").path("summary").path("words").stringValue() == tries[at].values().first().decision().why()
        }
        sentPayloads.size() == 2
        sentPayloads.withIndex().every { sent, at ->
            def summary = new com.lilradish.lite.domain.declaration.FieldName("summary")
            def complaint = new com.lilradish.lite.domain.declaration.FieldName("complaint")
            def refused = at == 0 ? null : new com.lilradish.lite.domain.inference.Payload.Refused(
                    [(summary): Json.of(tries[0].values().first().value().stringValue())],
                    [(summary): tries[0].values().first().decision().why()])
            def expected = com.lilradish.lite.domain.inference.Payload.toReview(requested[0],
                    [(complaint): requested[1]], refused, [(summary): Json.of(tries[at].values().first().value().stringValue())], [summary], [])
            // Historical sends retain the envelope under which they were stored.
            def historical = com.lilradish.lite.app.inference.EndpointJson.READER.readTree(CanonicalJson.write(expected))
            historical.put("envelope_version", 5)
            com.lilradish.lite.app.inference.EndpointJson.READER.readTree(sent) == historical
        }
        store.count("select count(*) from model_call_tool_uses where model_call_id in ('00000015-0000-4000-8000-000000001601', '00000015-0000-4000-8000-000000001602')") == 0
    }

    private Map<Integer, String> whereEachRunIs() {
        def runs = new Runs(store.session, new GroupRoles(store.session), store.transactionManager(), wiring.snapshots)
        numbered().collectEntries { number, run ->
            def view = runs.run(SUPPORT, run, GRACE)
            def positions = store.transactions().execute {
                StepPositions.of(wiring.snapshots.asRead(SUPPORT, run)).collect { it.getClass().simpleName }
            }
            [(number): "${view.state()} ${view.at()?.name()?.value() ?: '-'} ${positions}" as String]
        }
    }

    private Map<Integer, String> nextActs() {
        numbered().collectEntries { number, run ->
            def act = store.transactions().execute {
                EngineActs.next(wiring.snapshots.inTreeAsRead(SUPPORT, run), Deployments.DEEP_ENOUGH.maximumDepth())
            }
            [(number): act.getClass().simpleName]
        }
    }

    private Map<String, List<String>> stepPagesRead() {
        def steps = new RunSteps(store.session, new GroupRoles(store.session), wiring.snapshots,
                store.transactionManager(), Deployments.DEEP_ENOUGH, new com.lilradish.lite.app.currency.GroupCurrencies(store.session, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager()), new GroupRoles(store.session), com.lilradish.lite.testutil.library.DeployedModels.HELD), com.lilradish.lite.testutil.library.DeployedModels.HELD)
        def read = [listed: [], paged: []]
        numbered().each { number, run ->
            def stepIds = store.transactions().execute {
                wiring.snapshots.asRead(SUPPORT, run).steps().collect { it.planned().id() }
            }
            read.listed.addAll(steps.steps(SUPPORT, run, GRACE).steps().collect { "${number} ${it.name()}" as String })
            read.paged.addAll(stepIds.collect { WorkflowStepId step ->
                "${number} ${steps.step(SUPPORT, run, step, GRACE).step().name()}" as String
            })
        }
        read
    }

    /** Each run's one call: what its attempt sent, both counts, whether the model counted them, and the envelope. */
    private Map<Integer, String> callsAsStored() {
        // DB-SPECIFIC: PostgreSQL model_call_purpose enum labels select production calls.
        store.session.sql("""
                select run.number, sent.payload, call.sent_count, call.came_back_count, call.counted_by_model,
                       call.envelope_version
                  from model_calls call
                  join run_step_send_attempts sent on sent.run_step_send_attempt_id = call.run_step_send_attempt_id
                  join runs run on run.run_id = call.run_id
                 where call.purpose = 'produce'
                """).query { result, row ->
            [result.getInt(1), "${result.getString(2)} ${result.getLong(3)} ${result.getLong(4)} ${result.getBoolean(5)}" +
                    " ${result.getInt(6)}"]
        }.list().collectEntries { [(it[0]): it[1] as String] }
    }

    private static Map<Integer, String> historicalCalls() {
        def recorded = [
            2: ['Hello,\n\nMy card was charged twice for the wireless headphones I ordered (order 5530-1187), ' +
                    'once on the day I ordered and again a week later. Please refund the second charge.\n\n' +
                    'Thanks,\nSam Taylor', 738L, 35L],
            6: ['Hi,\n\nThe courier left my parcel (order 7781-0032) on the doorstep in the rain, ' +
                    'and the bookshelf inside is warped.\n\nThanks,\nMaria Lopez', 724L, 32L],
            7: ['Hello,\n\nThe cake stand I ordered for the 12th (order 3391-0475) only arrived on the 15th, ' +
                    'after the party. The tracking number was DEMO-TRACK-000000001.\n\nKind regards,\nOwen Price', 735L, 35L],
            8: ['Hello,\n\nI changed my billing address, but the invoice for the desk lamp I bought ' +
                    '(order 9042-6615) still shows the old one. Could you send a corrected invoice?\n\n' +
                    'Thank you,\nLena Fischer', 736L, 32L],
            12: ['Hello,\n\nThe refund for the coffee grinder I returned (order 4409-7763) went to a card ' +
                    'I cancelled last year. How do I get it back?\n\nRegards,\nDavid Kim', 728L, 33L],
        ]
        recorded.collectEntries { number, call ->
            def payload = CanonicalJson.write(Json.of([
                envelope_version: 4,
                instruction: 'Say which category the complaint falls under, which product it concerns and any order reference it gives.',
                takes: [complaint: call[0], channel: 'email'],
            ]))
            [(number): payload + " ${call[1]} ${call[2]} false 4" as String]
        }
    }

    /* As RunEngine.send builds what it sends and measures it, and as a call that came back is counted here. */
    private Map<Integer, String> callsAsTheEngineWritesThem(Set<Integer> numbers) {
        def runs = numbered()
        numbers.collectEntries { number ->
            def call = store.session.sql("""
                    select call.production_id, call.model, call.answer from model_calls call
                     where call.run_id = ?""").params(runs[number].value()).query { result, row ->
                [new ProductionId(result.getObject(1, UUID)), result.getString(2), result.getString(3)]
            }.single()
            def written = store.transactions().execute {
                def at = wiring.snapshots.inTreeAsRead(SUPPORT, runs[number])
                def step = at.run().steps().find { each -> each.tries().any { it.id() == call[0] } }
                def aTry = step.tries().find { it.id() == call[0] }
                def payload = CanonicalJson.write(RunPayloads.toProduce(step.planned(), StepInputs.took(at, step, aTry),
                        RunPayloads.refusedInWords(step, aTry.id()).orElse(null)))
                def question = step.planned().runs() as StepRuns.Question
                def envelope = Envelope.toProduce(Asking.told(question.gives(), question.lists()), question.instruction())
                def model = catalog.find(new ModelName(call[1])).orElseThrow()
                "${payload} ${model.unitsOf(SentText.measure(envelope, payload).characters())}" +
                        " ${model.unitsOf(CameBackMeasure.characters(call[2]))} false ${Envelope.VERSION}"
            }
            [(number): written as String]
        }
    }

    private Map<Integer, RunId> numbered() {
        store.session.sql("select number, run_id from runs order by number").query { result, row ->
            [result.getInt(1), new RunId(result.getObject(2, UUID))]
        }.list().collectEntries { [(it[0]): it[1]] }
    }

    private static Map<String, JsonValue> namedValues(JsonValue.JsonObject values) {
        values.members().collectEntries { [(it.name()): it.value()] }
    }

    private String root(int number) {
        // DB-SPECIFIC: text is a PostgreSQL target type.
        store.session.sql("select cast(root_run_id as text) from runs where number = ?").params(number)
                .query(String).single()
    }

    /** Every table a run's tree is written to that names its run, counted by tree, so a row added anywhere shows. */
    private Map<String, Map<String, Long>> rowsByRoot() {
        def tables = store.session.sql("""
                select table_name from information_schema.columns
                 where table_schema = 'app' and column_name = 'run_id' and table_name <> 'runs'
                """).query(String).list()
        assert tables.size() > 5, "no table names a run, so every tree would hold the same nothing"
        // DB-SPECIFIC: text is a PostgreSQL target type.
        def held = store.session.sql("select cast(root_run_id as text) from runs").query(String).list()
                .collectEntries { [(it): [:]] }
        tables.each { table ->
            store.session.sql(RUN_ROWS.formatted(table)).query { result, row ->
                held[result.getString("root")][result.getString("kind")] = result.getLong("held")
            }.list()
        }
        held
    }

    private LibraryStore seeded(String name) {
        def seeded = LibraryStore.forCase(server, name, true)
        DevelopmentSeed.appliedTo(seeded.database)
        seeded
    }
}
