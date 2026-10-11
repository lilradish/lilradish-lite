package com.lilradish.lite.app.run

import com.lilradish.lite.app.codestep.CodeRuns
import com.lilradish.lite.app.codestep.CodeSteps
import com.lilradish.lite.app.files.DatabaseFileContentStore
import com.lilradish.lite.app.files.FileDeployment
import com.lilradish.lite.app.files.FileOpenAdmission
import com.lilradish.lite.app.files.RunFiles
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.inference.ToolModelCalls
import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.domain.file.StoredFile
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.inference.ModelCalls
import com.lilradish.lite.domain.model.ModelCatalog
import com.lilradish.lite.domain.run.ReviewOutcome
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.WorkflowStepId
import com.lilradish.lite.testutil.ReadyServletStreams
import com.lilradish.lite.testutil.library.DeployedModels
import com.lilradish.lite.testutil.library.LibraryStore
import com.lilradish.lite.testutil.run.EngineCodeTasks
import com.lilradish.lite.web.ActAdmission
import com.lilradish.lite.web.CallerAdmission
import com.lilradish.lite.web.FileTransfers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.mockito.Mockito
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.support.GenericApplicationContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.util.unit.DataSize

/** Kept beside the engine because the fixture must name its package-private types. */
final class EngineWiring {

    final RunTree tree

    final RunSnapshots snapshots

    final EngineWrites writes

    final EngineExecutor executor

    final EngineTaskBarrier tasks

    final EngineCalls calls

    final RunEngine engine

    final StepActs acts

    final EngineRestart restart

    EngineWiring(LibraryStore store, ModelCalls modelCalls, CodeSteps release,
                 ModelCatalog catalog = DeployedModels.HELD, CodeSteps running = release,
                 RunDeployment deployment = Deployments.DEEP_ENOUGH,
                 FileDeployment fileLimits = fileDeployment(), SearchWays ways = new SearchWays([]),
                 ToolModelCalls toolCalls = withoutTools()) {
        def session = store.session
        tree = new RunTree(session)
        snapshots = new RunSnapshots(session, release, ways)
        writes = new EngineWrites(session)
        executor = EngineExecutors.of(store.database)
        tasks = EngineTaskBarrier.attach(executor)
        def ceilings = new CeilingReach(session, catalog, writes)
        def heldFiles = files(store, fileLimits)
        calls = new EngineCalls(toolCalls, modelCalls, store.transactions(), tree, writes, ceilings, snapshots, heldFiles)
        def codes = new EngineCodes(new CodeRuns(running), store.transactions(), tree, snapshots, writes, executor, heldFiles)
        engine = new RunEngine(session, store.transactions(), tree, snapshots, writes, executor, calls, codes, catalog,
                ceilings, new GroupRoles(session), deployment, heldFiles)
        acts = new StepActs(session, store.transactions(), new GroupRoles(session), tree, snapshots, writes, engine,
                heldFiles, fileLimits, catalog)
        restart = new EngineRestart(session, store.transactions(), tree, writes, calls, engine, executor)
    }

    static FileDeployment fileDeployment() {
        new FileDeployment(DataSize.ofMegabytes(10), 10, DataSize.ofGigabytes(1))
    }

    static ToolModelCalls withoutTools() {
        { request, progress -> throw new AssertionError("This workflow calls no model with tools") } as ToolModelCalls
    }

    static RunFiles files(LibraryStore store, FileDeployment fileLimits = fileDeployment()) {

        new RunFiles(store.session, new DatabaseFileContentStore(store.database), fileLimits)
    }

    /** The real download handler reached with the caller and group already admitted. */
    static MockHttpServletResponse downloaded(LibraryStore store, RunFiles files, GroupId group,
                                             RunId run, StoredFile file, UserId caller) {
        def request = new MockHttpServletRequest('GET', '/api/groups/' + group.value() + '/runs/' + run.value() +
                '/files/' + file.id())
        request.setAttribute(ActAdmission.name + '.group', group)
        request.setAttribute(CallerAdmission.name + '.caller', caller)
        def response = new MockHttpServletResponse()
        request.asyncSupported = true
        com.lilradish.lite.web.ReadyFileConnections.install(request)
        try (FileTransfers transfers = new FileTransfers(fileDeployment(), System::nanoTime, new ThreadPoolTaskExecutor())) {
            new RunFilesController(store.session, new GroupRoles(store.session), files, new FileOpenAdmission(), transfers)
                    .download(run.value().toString(), file.id().toString(),
                            ReadyServletStreams.request(request), ReadyServletStreams.response(response))
            assert !request.asyncStarted
        }
        response
    }

    /** Only this package may construct the records a real human review consumes. */
    void assure(GroupId group, RunId run, WorkflowStepId step, int number, UserId caller, Set<String> fields) {
        acts.review(group, run, step, number, caller,
                fields.collectEntries { [(it): new StepActs.ReviewedRecord(ReviewOutcome.ASSURED, null)] })
    }

    /** The real change service shares the run tree and engine held by this fixture. */
    void openAgain(LibraryStore store, GroupId group, RunId run, UserId caller) {
        new RunChanges(store.session, store.transactions(), new GroupRoles(store.session), tree, snapshots, engine)
                .openAgain(group, run, caller)
    }

    /** As the application starting does: what was out settled before it serves, then every run not stopped driven. */
    void started() {
        restart.afterSingletonsInstantiated()
        restart.start()
    }

    /** The application closing, as the context the pools belong to publishes it. */
    void closing() {
        def own = new GenericApplicationContext()
        executor.setApplicationContext(own)
        executor.onApplicationEvent(new ContextClosedEvent(own))
    }

    void close() {
        closing()
        executor.destroy()
    }

    void drained() {
        tasks.awaitDrained()
    }

    /** Both pools kept busy until the gate returned is opened, so whatever is handed over meanwhile queues. */
    CountDownLatch occupied() {
        def gate = new CountDownLatch(1)
        def busy = new CountDownLatch(4)
        2.times {
            executor.execute {
                busy.countDown()
                gate.await(60, TimeUnit.SECONDS)
            }
            EngineCodeTasks.run(executor) {
                busy.countDown()
                gate.await(60, TimeUnit.SECONDS)
            }
        }
        assert busy.await(10, TimeUnit.SECONDS)
        gate
    }

}
