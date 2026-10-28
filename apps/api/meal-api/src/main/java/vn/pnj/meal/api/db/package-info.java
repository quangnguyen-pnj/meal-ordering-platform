/**
 * Persistence model: one JPA entity per table of the Flyway schema, mapped column for column with
 * the quoted camelCase names the schema uses. Entities hold foreign keys as plain ids, without JPA
 * relationships, so nothing loads lazily behind a service's back; services join what they need
 * through repositories or {@code JdbcClient}. Postgres enum columns bind as {@code
 * PostgreSQLEnumJdbcType}; jsonb columns are written with {@code JdbcClient} and an explicit cast.
 * {@code createdAt} columns are filled by the database default.
 */
package vn.pnj.meal.api.db;
