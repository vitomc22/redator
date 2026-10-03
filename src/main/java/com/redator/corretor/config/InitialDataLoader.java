package com.redator.corretor.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class InitialDataLoader implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public InitialDataLoader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tema", Long.class);
        if (count == null || count == 0) {
            jdbcTemplate.update(
                    "INSERT INTO tema (id, titulo, recorte, motivadores) VALUES (?, ?, ?, ?)",
                    1,
                    "Educacao como direito fundamental",
                    "Como a educacao impacta a mobilidade social e a qualidade de vida?",
                    "A educacao e um direito humano essencial. O acesso a escola e a formacao ampliam oportunidades, reduz desigualdades estruturais e fortalece a cidadania."
            );
            jdbcTemplate.update(
                    "INSERT INTO tema (id, titulo, recorte, motivadores) VALUES (?, ?, ?, ?)",
                    2,
                    "Tecnologia e sociedade",
                    "Como a tecnologia transformou a vida cotidiana e criou novos desafios?",
                    "A tecnologia acelera a comunicacao, facilita o acesso a informacao e modifica o trabalho. Contudo, tambem produz desigualdades e impactos sociais relevantes."
            );
        }
    }
}
