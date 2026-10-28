/**
 * Persistence model for the P0 sign-in tables: JPA entities mapped column for column with the
 * quoted camelCase names the schema uses. The P1 tables are read and written with {@code
 * JdbcClient} by their services (docs/decisions.md, J2). Entities hold foreign keys as plain ids,
 * without JPA relationships, so nothing loads lazily behind a service's back; services join what
 * they need through repositories or {@code JdbcClient}. Postgres enum columns bind as {@code
 * PostgreSQLEnumJdbcType}; jsonb columns are written with {@code JdbcClient} and an explicit cast.
 * {@code createdAt} columns of these entities are filled by the database default, in UTC because
 * every connection runs {@code SET TIME ZONE 'UTC'} (application.yml).
 */
package vn.pnj.meal.api.db;
