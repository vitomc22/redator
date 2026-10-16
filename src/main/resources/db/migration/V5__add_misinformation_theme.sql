-- Tema usado no benchmark humano autorizado de desinformacao.
-- O recorte explicito evita avaliar a redacao contra um tema apenas aproximado.
INSERT OR IGNORE INTO tema (id, titulo, recorte, motivadores)
VALUES (
    3,
    'Desafios para o enfrentamento da desinformacao no Brasil',
    'Discuta os desafios para enfrentar a desinformacao no Brasil e apresente uma proposta de intervencao que respeite os direitos humanos.',
    'A desinformacao compromete o direito de acesso a informacao, fragiliza o debate publico e exige respostas que articulem educacao midiatica, transparencia das plataformas e preservacao da liberdade de expressao.'
);
