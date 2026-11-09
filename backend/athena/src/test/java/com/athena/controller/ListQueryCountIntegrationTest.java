package com.athena.controller;

import com.athena.ApiIntegrationTest;
import com.athena.dto.RegisterResponse;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The list pages must not run one query per row (the "N + 1" problem): a page
 * with a dozen rows takes as many database statements as a page with two.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ListQueryCountIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void listPagesRunTheSameNumberOfQueriesForTwoRowsAsForTwelve() throws Exception {
        registerWithRole("Adam", "ROLE_ADMIN");
        RegisterResponse hub = register("Hub");
        sendersTo(hub.getAccountNumber(), 0, 2);

        Map<String, Long> few = statementsPerPage(login("Adam"), login("Hub"));

        sendersTo(hub.getAccountNumber(), 2, 12);

        Map<String, Long> many = statementsPerPage(login("Adam"), login("Hub"));

        assertThat(many).as("statements per page with 12 rows (expected: as with 2 rows %s)", few)
                .isEqualTo(few);
    }

    /** Customers who each deposit, then send money to the hub: a different account on every row. */
    private void sendersTo(String hubAccount, int from, int to) throws Exception {
        for (int i = from; i < to; i++) {
            String name = "Sender" + i;
            register(name);
            String token = login(name);
            money(DEPOSIT, token, amount("100.00")).andExpect(status().isCreated());
            money(TRANSFER, token, transferTo(hubAccount, "10.00")).andExpect(status().isCreated());
        }
    }

    private Map<String, Long> statementsPerPage(String admin, String hub) throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String url : List.of("/api/oversight/transactions?size=50", "/api/admin/users?size=50")) {
            counts.put(url, statementsFor(admin, url));
        }
        counts.put("/api/transactions?size=50", statementsFor(hub, "/api/transactions?size=50"));
        return counts;
    }

    private long statementsFor(String token, String url) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        getAs(token, url).andExpect(status().isOk());
        return statistics.getPrepareStatementCount();
    }
}
