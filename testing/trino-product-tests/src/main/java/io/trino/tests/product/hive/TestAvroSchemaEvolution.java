/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.tests.product.hive;

import io.trino.tempto.AfterTestWithContext;
import io.trino.tempto.BeforeTestWithContext;
import io.trino.tempto.ProductTest;
import org.testng.annotations.Test;

import static io.trino.tempto.assertions.QueryAssert.Row.row;
import static io.trino.tempto.assertions.QueryAssert.assertQueryFailure;
import static io.trino.tempto.assertions.QueryAssert.assertThat;
import static io.trino.tempto.query.QueryExecutor.query;
import static io.trino.tests.product.TestGroups.AVRO;
import static io.trino.tests.product.utils.QueryExecutors.onHive;
import static java.lang.String.format;

public class TestAvroSchemaEvolution
        extends ProductTest
{
    private static final String TABLE_NAME = "product_tests_avro_table";
    // TODO move Avro schema files to classpath and use tempto SshClient to upload them
    private static final String ORIGINAL_SCHEMA = "file:///docker/presto-product-tests/avro/original_schema.avsc";
    private static final String CREATE_TABLE = format("" +
                    "CREATE TABLE %s (dummy_col VARCHAR)" +
                    "WITH (" +
                    "format='AVRO', " +
                    "avro_schema_url='%s'" +
                    ")",
            TABLE_NAME,
            ORIGINAL_SCHEMA);
    private static final String RENAMED_COLUMN_SCHEMA = "file:///docker/presto-product-tests/avro/rename_column_schema.avsc";
    private static final String REMOVED_COLUMN_SCHEMA = "file:///docker/presto-product-tests/avro/remove_column_schema.avsc";
    private static final String ADDED_COLUMN_SCHEMA = "file:///docker/presto-product-tests/avro/add_column_schema.avsc";
    private static final String CHANGE_COLUMN_TYPE_SCHEMA = "file:///docker/presto-product-tests/avro/change_column_type_schema.avsc";
    private static final String INCOMPATIBLE_TYPE_SCHEMA = "file:///docker/presto-product-tests/avro/incompatible_type_schema.avsc";
    private static final String SELECT_STAR = "SELECT * FROM " + TABLE_NAME;
    private static final String COLUMNS_IN_TABLE = "SHOW COLUMNS IN " + TABLE_NAME;

    @BeforeTestWithContext
    public void createAndLoadTable()
    {
        query(CREATE_TABLE);
        query(format("INSERT INTO %s VALUES ('string0', 0)", TABLE_NAME));
    }

    @AfterTestWithContext
    public void dropTestTable()
    {
        query(format("DROP TABLE IF EXISTS %s", TABLE_NAME));
    }

    @Test(groups = AVRO)
    public void testSelectTable()
    {
        assertThat(query(format("SELECT string_col FROM %s", TABLE_NAME)))
                .containsExactlyInOrder(row("string0"));
    }

    @Test(groups = AVRO)
    public void testInsertAfterSchemaEvolution()
    {
        assertThat(query(SELECT_STAR))
                .containsExactlyInOrder(row("string0", 0));

        alterTableSchemaTo(ADDED_COLUMN_SCHEMA);
        query(format("INSERT INTO %s VALUES ('string1', 1, 101)", TABLE_NAME));
        assertThat(query(SELECT_STAR))
                .containsOnly(
                        row("string0", 0, 100),
                        row("string1", 1, 101));
    }

    @Test(groups = AVRO)
    public void testSchemaEvolutionWithIncompatibleType()
    {
        assertThat(query(COLUMNS_IN_TABLE))
                .containsExactlyInOrder(
                        row("string_col", "varchar", "", ""),
                        row("int_col", "integer", "", ""));
        assertThat(query(SELECT_STAR))
                .containsExactlyInOrder(row("string0", 0));

        alterTableSchemaTo(INCOMPATIBLE_TYPE_SCHEMA);
        assertQueryFailure(() -> query(SELECT_STAR))
                .hasMessageContaining("Found int, expecting string");
    }

    @Test(groups = AVRO)
    public void testSchemaEvolution()
    {
        assertThat(query(COLUMNS_IN_TABLE))
                .containsExactlyInOrder(
                        row("string_col", "varchar", "", ""),
                        row("int_col", "integer", "", ""));
        assertThat(query(SELECT_STAR))
                .containsExactlyInOrder(row("string0", 0));

        alterTableSchemaTo(CHANGE_COLUMN_TYPE_SCHEMA);
        assertThat(query(COLUMNS_IN_TABLE))
                .containsExactlyInOrder(
                        row("string_col", "varchar", "", ""),
                        row("int_col", "bigint", "", ""));
        assertThat(query(SELECT_STAR))
                .containsExactlyInOrder(row("string0", 0));

        alterTableSchemaTo(ADDED_COLUMN_SCHEMA);
        assertThat(query(COLUMNS_IN_TABLE))
                .containsExactlyInOrder(
                        row("string_col", "varchar", "", ""),
                        row("int_col", "integer", "", ""),
                        row("int_col_added", "integer", "", ""));
        assertThat(query(SELECT_STAR))
                .containsExactlyInOrder(row("string0", 0, 100));

        alterTableSchemaTo(REMOVED_COLUMN_SCHEMA);
        assertThat(query(COLUMNS_IN_TABLE))
                .containsExactlyInOrder(row("int_col", "integer", "", ""));
        assertThat(query(SELECT_STAR))
                .containsExactlyInOrder(row(0));

        alterTableSchemaTo(RENAMED_COLUMN_SCHEMA);
        assertThat(query(COLUMNS_IN_TABLE))
                .containsExactlyInOrder(
                        row("string_col", "varchar", "", ""),
                        row("int_col_renamed", "integer", "", ""));
        assertThat(query(SELECT_STAR))
                .containsExactlyInOrder(row("string0", null));
    }

    @Test(groups = AVRO)
    public void testSchemaWhenUrlIsUnset()
    {
        assertThat(query(COLUMNS_IN_TABLE))
                .containsExactlyInOrder(
                        row("string_col", "varchar", "", ""),
                        row("int_col", "integer", "", ""));
        assertThat(query(SELECT_STAR))
                .containsExactlyInOrder(row("string0", 0));

        onHive().executeQuery(format("ALTER TABLE %s UNSET TBLPROPERTIES('avro.schema.url')", TABLE_NAME));
        assertThat(query(COLUMNS_IN_TABLE))
                .containsExactlyInOrder(
                        row("dummy_col", "varchar", "", ""));
    }

    @Test(groups = AVRO)
    public void testCreateTableLike()
    {
        String createTableLikeName = "test_avro_like";
        query(format(
                "CREATE TABLE %s (LIKE %s INCLUDING PROPERTIES)",
                createTableLikeName,
                TABLE_NAME));

        query(format("INSERT INTO %s VALUES ('string0', 0)", createTableLikeName));

        assertThat(query(format("SELECT string_col FROM %s", createTableLikeName)))
                .containsExactlyInOrder(row("string0"));
        query("DROP TABLE IF EXISTS " + createTableLikeName);
    }

    private void alterTableSchemaTo(String schema)
    {
        onHive().executeQuery(format("ALTER TABLE %s SET TBLPROPERTIES('avro.schema.url'='%s')", TABLE_NAME, schema));
    }
}
