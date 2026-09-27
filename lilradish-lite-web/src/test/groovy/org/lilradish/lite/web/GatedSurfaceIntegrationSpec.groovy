package org.lilradish.lite.web

import static java.nio.charset.StandardCharsets.ISO_8859_1

import java.nio.file.Path
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.currency.GroupCurrencies
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.groupregister.GroupChanges
import org.lilradish.lite.app.groupregister.GroupRegister
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.app.library.EntryChanges
import org.lilradish.lite.app.library.Library
import org.lilradish.lite.app.library.Offers
import org.lilradish.lite.app.library.QuestionDrafts
import org.lilradish.lite.app.library.Questions
import org.lilradish.lite.app.library.ReferenceListDrafts
import org.lilradish.lite.app.library.ReferenceLists
import org.lilradish.lite.app.library.VersionChanges
import org.lilradish.lite.app.library.WorkflowDrafts
import org.lilradish.lite.app.library.Workflows
import org.lilradish.lite.app.measurements.Measurements
import org.lilradish.lite.app.members.Members
import org.lilradish.lite.app.members.MembershipChanges
import org.lilradish.lite.app.people.People
import org.lilradish.lite.app.pool.PoolChanges
import org.lilradish.lite.app.pool.PoolPeople
import org.lilradish.lite.app.run.RunChanges
import org.lilradish.lite.app.run.RunList
import org.lilradish.lite.app.run.RunSteps
import org.lilradish.lite.app.run.Runs
import org.lilradish.lite.app.run.StepActs
import org.lilradish.lite.app.standing.Holdings
import org.lilradish.lite.app.start.StartRuns
import org.lilradish.lite.web.fixture.ActProbeController
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.server.PathContainer
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import spock.lang.Specification

/**
 * What every address this application answers has to have said about itself. The gate refuses an act
 * a caller does not hold, but only where a handler declared one — so a handler that declared nothing
 * is not refused by it, and nothing at the address itself says whether that was decided or forgotten.
 *
 * <p>Read off the mappings the dispatcher actually resolved rather than off the source, because what
 * decides is the annotation on the method that was selected: a declaration written on a method no
 * mapping reaches would satisfy any rule read from a file and none read from here.
 *
 * <p>Asked in both directions. An address outside the prefix is served to whoever asks and has no
 * caller to demand anything of, so a declaration out there is a gate that reads as though it holds.
 * A handler mapped on both sides at once belongs to neither direction, so it is refused outright
 * before either is asked — otherwise it is a handler no rule here covers.
 *
 * <p>The mappings are only the addresses a handler method answers. A routing function answers
 * addresses too and carries no method to declare anything on, so the gate could not read one even if
 * it were reached; that this application declares none is asserted off what it compiles to, which is
 * the one reading a slice cannot make vacuous.
 */
@WebMvcTest
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@Import(ActProbeController)
@MockitoBean(types = [UserIdentification, EstateRoleGrants, GroupRoles, Holdings, PoolPeople, PoolChanges, People,
        GroupRegister, GroupChanges, Members, MembershipChanges, Library, EntryChanges, VersionChanges, Measurements,
        Questions, QuestionDrafts, Workflows, WorkflowDrafts, ReferenceLists, ReferenceListDrafts, GroupCurrencies, Runs,
        RunChanges, RunList, Offers, RunSteps, StepActs, StartRuns])
class GatedSurfaceIntegrationSpec extends Specification {

    static final List<Class> DECLARATIONS = [ActRequired, GroupPermissionRequired, GroupMembershipRequired, NoActRequired]

    /** What a handler may ask inside a group, and nothing it may ask anywhere else. */
    static final List<Class> ASKED_IN_A_GROUP = [GroupPermissionRequired, GroupMembershipRequired]

    static final Path COMPILED = Path.of(CallerAdmission.protectionDomain.codeSource.location.toURI())

    static final String ROUTES_WITHOUT_A_METHOD = "org/springframework/web/servlet/function/RouterFunction"

    @Autowired
    private RequestMappingHandlerMapping mappings

    /** The door's own judgement, so a pattern is judged by whatever decides it at request time. */
    private static boolean underThePrefix(String pattern) {
        CallerAdmission.answersThisApplication(PathContainer.parsePath(pattern))
    }

    private Map addressedBy(boolean guarded) {
        mappings.handlerMethods.findAll { mapped, handler ->
            mapped.patternValues.every { underThePrefix(it) == guarded }
        }
    }

    private static List<String> namesOf(Map handlers) {
        handlers.values().collect { it.method.name }.toSorted()
    }

    def "no handler answers both inside this application's prefix and outside it"() {
        expect:
        namesOf(mappings.handlerMethods.findAll { mapped, handler ->
            mapped.patternValues.any { underThePrefix(it) } && mapped.patternValues.any { !underThePrefix(it) }
        }) == []
    }

    def "every address under this application's prefix declares what it asks of its caller"() {
        given:
        def answered = addressedBy(true)

        expect: "addresses to judge, an empty mapping satisfying any rule asked of it"
        !answered.isEmpty()

        and: "none of them silent, which is the shape a handler nobody gated has"
        namesOf(answered.findAll { mapped, handler -> declared(handler) == 0 }) == []

        and: "and none of them saying more than one, which would leave which of them decides unsaid"
        namesOf(answered.findAll { mapped, handler -> declared(handler) > 1 }) == []

        and: "over a surface that says each thing somewhere, one kind alone leaving the others untested"
        DECLARATIONS.every { declaration ->
            answered.any { mapped, handler -> handler.getMethodAnnotation(declaration) != null }
        }
    }

    def "no address outside that prefix declares one, nothing out there having a caller to ask of"() {
        expect:
        namesOf(addressedBy(false).findAll { mapped, handler -> declared(handler) > 0 }) == []
    }

    /** The gate reads the group off the address, so an address naming none leaves it nothing to admit into. */
    def "every handler asking anything in a group answers under the address naming that group"() {
        given:
        def inGroups = mappings.handlerMethods.findAll { mapped, handler -> handler.getMethodAnnotation(declaration) != null }

        expect: "handlers to judge, an empty mapping satisfying any rule asked of it"
        !inGroups.isEmpty()

        and:
        namesOf(inGroups.findAll { mapped, handler ->
            !mapped.patternValues.every { it.startsWith(ActAdmission.IN_A_GROUP + "/") }
        }) == []

        where:
        declaration << ASKED_IN_A_GROUP
    }

    /**
     * An address inside a group is the group's, so what it asks is asked there: an act of the estate
     * gating one would let in somebody who holds no role in the group at all.
     */
    def "every handler answering inside a group asks a permission there, or membership of it"() {
        given:
        def insideGroups = mappings.handlerMethods.findAll { mapped, handler ->
            mapped.patternValues.any { it.startsWith(ActAdmission.IN_A_GROUP + "/") }
        }

        expect: "handlers to judge, an empty mapping satisfying any rule asked of it"
        !insideGroups.isEmpty()

        and:
        namesOf(insideGroups.findAll { mapped, handler ->
            ASKED_IN_A_GROUP.every { handler.getMethodAnnotation(it) == null }
        }) == []
    }

    /** Every change inside a group asks a permission there; membership guarding one lets every member make it. */
    def "every handler asking membership alone answers reads and nothing else"() {
        given:
        def membershipAlone = mappings.handlerMethods.findAll { mapped, handler ->
            handler.getMethodAnnotation(GroupMembershipRequired) != null
        }

        expect: "handlers to judge, an empty mapping satisfying any rule asked of it"
        !membershipAlone.isEmpty()

        and:
        namesOf(membershipAlone.findAll { mapped, handler ->
            def methods = mapped.methodsCondition.methods
            methods.isEmpty() || !([RequestMethod.GET, RequestMethod.HEAD] as Set).containsAll(methods)
        }) == []
    }

    private static int declared(handler) {
        DECLARATIONS.count { handler.getMethodAnnotation(it) != null }
    }

    /**
     * Read off the archive rather than off the context, a slice registering no routing function
     * whether or not one is declared — so the reading a slice makes is green either way.
     */
    def "nothing this application ships routes an address without a handler method to declare on"() {
        given:
        def compiled = []
        COMPILED.toFile().eachFileRecurse { if (it.name.endsWith(".class")) compiled << it }

        expect: "classes to judge, an empty walk satisfying any rule asked of it"
        !compiled.isEmpty()

        and:
        compiled.findAll { new String(it.readBytes(), ISO_8859_1).contains(ROUTES_WITHOUT_A_METHOD) }*.name == []
    }
}
