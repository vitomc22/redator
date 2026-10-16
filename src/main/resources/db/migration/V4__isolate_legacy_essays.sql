-- Redações anteriores ao ciclo de treino eram fixtures, benchmarks ou registros sem dono.
-- Elas permanecem auditáveis, mas não aparecem como histórico do aluno piloto.
UPDATE essay SET student_id = 'legacy' WHERE student_id = 'pilot-student';
