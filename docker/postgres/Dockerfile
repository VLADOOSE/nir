# Postgres для АИС с локалью en_US.UTF-8. КРИТИЧНО: под C/POSIX pg_trgm даёт ПУСТЫЕ триграммы
# для кириллицы → реестр-матчинг молча возвращает 0 (CLAUDE.md §5/§11). Debian-образ + localedef.
FROM postgres:16
RUN localedef -i en_US -c -f UTF-8 -A /usr/share/locale/locale.alias en_US.UTF-8
ENV LANG=en_US.UTF-8
# накатывается ОДИН раз при первичной инициализации пустого тома (после создания БД nirdb)
COPY initdb/01-tune.sql /docker-entrypoint-initdb.d/01-tune.sql
