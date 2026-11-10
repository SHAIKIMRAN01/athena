package com.athena.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

/**
 * Least privilege for the database.
 *
 * <p>When the schema owner and the app's own login role differ
 * ({@code DB_OWNER_USERNAME} and {@code DB_USERNAME}), this connects once as
 * the owner, before anything else uses the database, and makes sure that:
 * <ul>
 *   <li>the group role {@value #WRITER_ROLE} exists; {@code schema.sql}
 *       grants it exactly the privileges the app needs, and no UPDATE or
 *       DELETE on the history tables;</li>
 *   <li>the app's login role exists, with the password in
 *       {@code DB_PASSWORD}, is not a superuser, and is a member of
 *       {@value #WRITER_ROLE}.</li>
 * </ul>
 * If the owner may not create roles, startup stops with an explanation
 * rather than run with more rights than intended. When one user does
 * everything (owner and app the same), nothing happens.
 */
@Component
public class DatabaseRolesProvisioner implements BeanPostProcessor {

    public static final String WRITER_ROLE = "athena_writer";

    private static final Logger log = LoggerFactory.getLogger(DatabaseRolesProvisioner.class);
    private static final Pattern ROLE_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    private final Environment environment;
    private volatile boolean done;

    public DatabaseRolesProvisioner(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource && !done) {
            done = true;
            String url = environment.getProperty("spring.datasource.url");
            String appRole = environment.getProperty("spring.datasource.username", "");
            String appPassword = environment.getProperty("spring.datasource.password", "");
            String owner = environment.getProperty("spring.sql.init.username", "");
            String ownerPassword = environment.getProperty("spring.sql.init.password", "");
            if (!owner.isBlank() && !owner.equals(appRole)) {
                provision(url, owner, ownerPassword, appRole, appPassword);
            }
        }
        return bean;
    }

    /**
     * Creates or updates the roles as described above. Package-visible so a
     * test can run it with a role name of its own.
     */
    static void provision(String url, String owner, String ownerPassword, String appRole, String appPassword) {
        if (!ROLE_NAME.matcher(appRole).matches()) {
            throw new IllegalStateException("DB_USERNAME '" + appRole + "' is not a valid role name for the app:"
                    + " use lower-case letters, digits and _, for example athena_app");
        }
        if (appPassword == null || appPassword.isBlank()) {
            throw new IllegalStateException("DB_PASSWORD must be set for the app's database role " + appRole);
        }

        try (Connection connection = DriverManager.getConnection(url, owner, ownerPassword);
             Statement statement = connection.createStatement()) {

            if (!roleExists(connection, WRITER_ROLE)) {
                statement.execute("CREATE ROLE " + WRITER_ROLE + " NOLOGIN");
            }

            String role = quoteIdentifier(connection, appRole);
            String password = quoteLiteral(connection, appPassword);
            Boolean superuser = superuser(connection, appRole);
            if (superuser == null) {
                statement.execute("CREATE ROLE " + role
                        + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD " + password);
            } else if (superuser) {
                throw new IllegalStateException("The app's database role " + appRole
                        + " is a superuser; it must be an ordinary role for least privilege");
            } else {
                statement.execute("ALTER ROLE " + role + " WITH LOGIN PASSWORD " + password);
            }
            statement.execute("GRANT " + WRITER_ROLE + " TO " + role);

            log.info("Database roles: the app connects as {} (a member of {}); the schema owner is {}",
                    appRole, WRITER_ROLE, owner);
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not prepare the app's database role " + appRole
                    + " as the owner " + owner + ": " + failure.getMessage()
                    + ". Check DB_OWNER_USERNAME and DB_OWNER_PASSWORD; the owner must be allowed to"
                    + " create roles (CREATEROLE). To run without a restricted role instead, remove"
                    + " DB_OWNER_USERNAME and DB_OWNER_PASSWORD and set DB_USERNAME and DB_PASSWORD"
                    + " to the owner's.", failure);
        }
    }

    private static boolean roleExists(Connection connection, String name) throws SQLException {
        return superuser(connection, name) != null;
    }

    /** null if the role does not exist. */
    private static Boolean superuser(Connection connection, String name) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT rolsuper FROM pg_roles WHERE rolname = ?")) {
            query.setString(1, name);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getBoolean(1) : null;
            }
        }
    }

    private static String quoteIdentifier(Connection connection, String name) throws SQLException {
        return quote(connection, "SELECT quote_ident(?)", name);
    }

    private static String quoteLiteral(Connection connection, String value) throws SQLException {
        return quote(connection, "SELECT quote_literal(?)", value);
    }

    private static String quote(Connection connection, String sql, String value) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, value);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }
}
