package org.lilradish.lite.app.listing

import groovy.transform.TupleConstructor
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Connection
import java.sql.ResultSet
import org.lilradish.lite.domain.listing.ListColumn
import org.lilradish.lite.domain.listing.ListCursor
import org.lilradish.lite.domain.listing.ListFilter
import org.lilradish.lite.domain.listing.ListOrder
import org.lilradish.lite.domain.listing.ListPage
import org.lilradish.lite.domain.listing.ListPosition
import org.lilradish.lite.domain.listing.ListQuery
import org.lilradish.lite.domain.listing.ListRow
import org.lilradish.lite.domain.listing.ListShape
import org.lilradish.lite.domain.listing.ListValueKind
import org.postgresql.Driver
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Whether reading a list a page at a time yields exactly what one statement ordering the whole table
 * yields. That is a question of the page condition agreeing with the order, so it is asked of a table
 * of no list in particular: one column of each kind a list sorts by, every one of them full of ties,
 * the column that may be empty holding nothing in many rows, and text differing only by case or by how
 * an accent is written.
 *
 * <p>The table holds one row more than two pages, and the filter keeps exactly two pages of it, so the
 * last page is reached both with a row left over and with none. Resuming after every row in turn puts
 * a page boundary between every pair of rows.
 *
 * <p>The whole order is written out by hand below rather than taken from the statements under test,
 * and asked of two databases on one server, one defaulting to the C locale and one to ICU, so a
 * collation left to either default is wrong on one of them. Which order is the right one for a reader
 * is each list's own question, answered by writing its rows out by hand in its own spec.
 */
class KeysetPagesIntegrationSpec extends Specification {

    static final String CAPITAL_E_ACUTE = Character.toString(0xC9)

    static final String SMALL_E_ACUTE = Character.toString(0xE9)

    static final String COMBINING_ACUTE = Character.toString(0x0301)

    static final String CAPITAL_A_RING = Character.toString(0xC5)

    static final String SMALL_A_DIAERESIS = Character.toString(0xE4)

    static final int ROWS = 2 * ListPage.SIZE + 1

    static final List<String> KEY_PREFIXES = ["k", "K", CAPITAL_A_RING]

    static final List<String> NAMES = [null, "Alan", "alan", "ALAN", CAPITAL_E_ACUTE + "mile", "E" + COMBINING_ACUTE + "mile",
                                       SMALL_E_ACUTE + "mile", "zed", "Zed", null, CAPITAL_A_RING + "sa"]

    static final List<String> LABELS = ["b", "B", "a", SMALL_A_DIAERESIS, "A"]

    static final List<Long> SIZES = [Long.MIN_VALUE, -1L, 0L, 0L, 7L, Long.MAX_VALUE]

    /** Held by every key but the last row's, which the filter therefore leaves out. */
    static final String KEPT = "-"

    static final ListFilter NARROWED = new ListFilter(KEPT)

    static final KeysetStatements<Column> STATEMENTS = new KeysetStatements<>(
            "listed",
            Column.KEY,
            new ListOrder<>(Column.KEY, false),
            "select listed.key, listed.name, listed.label, listed.size from listed where true",
            "strpos(listed.key, ${KeysetStatements.TYPED}) > 0",
            [new SortExpression<>(Column.KEY, "listed.key", "ucs_basic"),
             new SortExpression<>(Column.NAME, "listed.name", '"unicode"'),
             new SortExpression<>(Column.LABEL, "listed.label", '"C"'),
             new SortExpression<>(Column.SIZE, "listed.size", null)],
            [:])

    static final ListShape<Column> LISTED = STATEMENTS.shape()

    /** The whole order, by hand: each column in its direction, the empty last, ties by key ascending. */
    static final Map<Column, String> WHOLE_ORDER = [
            (Column.KEY)  : "key collate ucs_basic %s",
            (Column.NAME) : 'name collate "unicode" %s nulls last, key collate ucs_basic',
            (Column.LABEL): 'label collate "C" %s, key collate ucs_basic',
            (Column.SIZE) : "size %s, key collate ucs_basic",
    ]

    static final List<ListOrder<Column>> EVERY_ORDER = Column.values().collectMany {
        [new ListOrder<>(it, false), new ListOrder<>(it, true)]
    }

    static final List<String> DEFAULTS = ["postgres", "icu_default"]

    static final RowMapper<Row> READ = { ResultSet result, int number ->
        new Row(result.getString("key"), result.getString("name"), result.getString("label"), result.getLong("size"))
    } as RowMapper<Row>

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    Map<String, JdbcClient> databases = [:]

    enum Column implements ListColumn {
        KEY("key", ListValueKind.TEXT),
        NAME("name", ListValueKind.NULLABLE_TEXT),
        LABEL("label", ListValueKind.TEXT),
        SIZE("size", ListValueKind.LONG)

        final String spelt

        final ListValueKind valueKind

        Column(String spelt, ListValueKind valueKind) {
            this.spelt = spelt
            this.valueKind = valueKind
        }

        @Override
        String published() {
            spelt
        }

        @Override
        ListValueKind kind() {
            valueKind
        }
    }

    @TupleConstructor
    static class Row implements ListRow<Column> {
        String key
        String name
        String label
        Long size

        @Override
        Object valueIn(Column column) {
            switch (column) {
                case Column.KEY: return key
                case Column.NAME: return name
                case Column.LABEL: return label
                default: return size
            }
        }
    }

    def setupSpec() {
        server.postgresDatabase.connection.withCloseable { Connection administration ->
            administration.createStatement().withCloseable { statement ->
                statement.execute("""
                        create database icu_default template template0 encoding 'UTF8'
                            locale_provider icu icu_locale 'und' locale 'C'
                        """)
            }
        }
        DEFAULTS.each { name ->
            def session = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
                    "jdbc:postgresql://localhost:${server.port}/${name}", "postgres", ""))
            session.sql("create table listed (key text primary key, name text, label text not null, size bigint not null)")
                    .update()
            (0..<ROWS).each { index ->
                def joined = index < ROWS - 1 ? KEPT : "+"
                session.sql("insert into listed (key, name, label, size) values (?, ?, ?, ?)")
                        .params(KEY_PREFIXES[index % KEY_PREFIXES.size()] + joined + String.format(Locale.ROOT, "%03d", index),
                                NAMES[index % NAMES.size()], LABELS[index % LABELS.size()], SIZES[index % SIZES.size()])
                        .update()
            }
            databases[name] = session
        }
        // The whole point of the second database: had it a C default too, every order would pass there
        // for the same reason as on the first.
        assert DEFAULTS.collect { defaultProviderOf(it) } == ["c", "i"]
    }

    /** A difference is only shown by a table in which there is one to show: an empty or tie-free table passes anyway. */
    def "the table holds a row past two pages, a column holding nothing in some rows, and ties in every column"() {
        when:
        def rows = databases.postgres.sql("select key, name, label, size from listed").query(READ).list()

        then:
        rows.size() == 2 * ListPage.SIZE + 1
        rows.count { it.name == null } > 1

        and: "every column but the key repeats a value, and the key does not"
        rows*.key.toSet().size() == rows.size()
        [rows*.name, rows*.label, rows*.size].every { it.toSet().size() < it.size() }

        and: "names differing only by case, and one name written with its accent composed and decomposed"
        rows*.name.containsAll(["Alan", "alan", "ALAN", CAPITAL_E_ACUTE + "mile", "E" + COMBINING_ACUTE + "mile"])

        and: "and the filter keeps exactly two pages of it"
        rows.count { it.key.contains(KEPT) } == 2 * ListPage.SIZE
    }

    def "read a page at a time from cursor to cursor, every order yields every row once, in the order one statement reads them all"() {
        given:
        def query = new ListQuery<>("", order, filter)
        def whole = wholeOrder(databases[database], order, filter)*.key

        when:
        def pages = pagesOf(databases[database], query)

        then:
        pages.flatten() == whole

        and: "no row twice, in as few pages as a page's size allows, every one full but the last, and none of them empty"
        pages.flatten().toSet().size() == whole.size()
        pages.size() == Math.ceil(whole.size() / ListPage.SIZE) as int
        pages.init().every { it.size() == ListPage.SIZE }
        !pages.last().isEmpty()

        where:
        [database, order, filter] << [DEFAULTS, EVERY_ORDER, [null, NARROWED]].combinations()
    }

    /** Whatever row a page ends on, the next begins at the row after it and at no other. */
    def "resumed after any row, a page holds the rows that follow it in the whole order, and goes on exactly where more follow"() {
        given:
        def query = new ListQuery<>("", order, filter)
        def whole = wholeOrder(databases[database], order, filter)

        when:
        def resumed = whole.indices.collect { index ->
            def row = whole[index]
            KeysetPages.read(databases[database], STATEMENTS, query, new ListPosition(row.valueIn(order.column()), row.key), READ)
        }

        then:
        whole.indices.every { index ->
            resumed[index].rows()*.key == whole.drop(index + 1).take(ListPage.SIZE)*.key
        }

        and: "a next page is offered exactly where rows are left beyond the one read"
        whole.indices.every { index ->
            (resumed[index].next() != null) == (whole.size() - index - 1 > ListPage.SIZE)
        }

        where:
        [database, order, filter] << [DEFAULTS, EVERY_ORDER, [null, NARROWED]].combinations()
    }

    private static List<List<String>> pagesOf(JdbcClient database, ListQuery<Column> query) {
        def pages = []
        ListPosition after = null
        while (pages.size() <= ROWS) {
            def page = KeysetPages.read(database, STATEMENTS, query, after, READ)
            pages << page.rows()*.key
            if (page.next() == null) {
                return pages
            }
            after = ListCursor.resume(LISTED, query, ListCursor.mint(LISTED, query, page.next()))
        }
        throw new IllegalStateException("More pages than the table has rows: ${pages}")
    }

    private static List<Row> wholeOrder(JdbcClient database, ListOrder<Column> order, ListFilter filter) {
        def narrowed = filter == null ? "" : "where strpos(key, :filter) > 0"
        def ordered = WHOLE_ORDER[order.column()].formatted(order.descending() ? "desc" : "asc")
        def statement = database.sql("select key, name, label, size from listed ${narrowed} order by ${ordered}" as String)
        (filter == null ? statement : statement.param("filter", filter.text())).query(READ).list()
    }

    private String defaultProviderOf(String database) {
        databases[database].sql("select datlocprovider::text from pg_database where datname = current_database()")
                .query(String)
                .single()
    }
}
