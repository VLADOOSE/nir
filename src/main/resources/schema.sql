DROP TABLE IF EXISTS med_equipment_offer CASCADE;
DROP TABLE IF EXISTS med_equipment_request CASCADE;
DROP TABLE IF EXISTS med_equipment CASCADE;
DROP TABLE IF EXISTS company_member CASCADE;
DROP TABLE IF EXISTS company CASCADE;
DROP TABLE IF EXISTS tender_step CASCADE;
DROP TABLE IF EXISTS tender CASCADE;
DROP TABLE IF EXISTS tender_founder CASCADE;


CREATE TABLE company (
                         company_id SERIAL PRIMARY KEY,
                         inn INTEGER NOT NULL,
                         name VARCHAR(255)
);


CREATE TABLE tender_founder (
                                tndr_fndr_id SERIAL PRIMARY KEY,
                                inn INTEGER NOT NULL,
                                org_name VARCHAR(255)
);


CREATE TABLE tender (
                        tndr_id SERIAL PRIMARY KEY,
                        cost NUMERIC(15,2) NOT NULL,
                        tndr_fndr_id INTEGER REFERENCES tender_founder (tndr_fndr_id) ON DELETE SET NULL
);


CREATE TABLE company_member (
                                company_member_id SERIAL,
                                tndr_id INTEGER NOT NULL,
                                company_id INTEGER NOT NULL,
                                result VARCHAR(255),
                                PRIMARY KEY (company_member_id, tndr_id, company_id),
                                FOREIGN KEY (tndr_id) REFERENCES tender (tndr_id) ON DELETE RESTRICT,
                                FOREIGN KEY (company_id) REFERENCES company (company_id) ON DELETE RESTRICT
);


CREATE TABLE med_equipment (
                               med_equip_id SERIAL PRIMARY KEY,
                               name VARCHAR(255),
                               cost INTEGER,
                               spec VARCHAR(500),
                               manufact VARCHAR(255)
);


CREATE TABLE med_equipment_request (
                                       med_equipment_request_id SERIAL,
                                       tndr_id           INTEGER NOT NULL,
                                       med_equip_id      INTEGER NOT NULL,
                                       med_equip_name    VARCHAR(255),
                                       cost              INTEGER,
                                       spec              VARCHAR(500),
                                       PRIMARY KEY (med_equipment_request_id, tndr_id, med_equip_id),
                                       FOREIGN KEY (tndr_id) REFERENCES tender (tndr_id) ON DELETE RESTRICT,
                                       FOREIGN KEY (med_equip_id) REFERENCES med_equipment (med_equip_id) ON DELETE RESTRICT
);

CREATE TABLE med_equipment_offer (
                                     med_equipment_offer_id SERIAL,
                                     cost                   INTEGER,
                                     spec                   VARCHAR(500),
                                     name                   VARCHAR(255),
                                     brand                  VARCHAR(255),
                                     med_equipment_request_id INTEGER NOT NULL,
                                     tndr_id                INTEGER NOT NULL,
                                     med_equip_id           INTEGER NOT NULL,
                                     company_id             INTEGER NOT NULL,
                                     company_member_id      INTEGER NOT NULL,
                                     PRIMARY KEY (med_equipment_offer_id, med_equipment_request_id, tndr_id, med_equip_id, company_id, company_member_id),
                                     FOREIGN KEY (med_equipment_request_id, tndr_id, med_equip_id)
                                         REFERENCES med_equipment_request (med_equipment_request_id, tndr_id, med_equip_id)
                                         ON DELETE RESTRICT,
                                     FOREIGN KEY (company_member_id, tndr_id, company_id)
                                         REFERENCES company_member (company_member_id, tndr_id, company_id)
                                         ON DELETE RESTRICT
);

CREATE TABLE tender_step (
                             tender_step_id SERIAL,
                             tndr_id        INTEGER NOT NULL,
                             start_date     DATE,
                             end_date       DATE,
                             step_name      VARCHAR(255),
                             PRIMARY KEY (tender_step_id, tndr_id),
                             FOREIGN KEY (tndr_id) REFERENCES tender (tndr_id) ON DELETE RESTRICT
);
CREATE TABLE facility (
                          id BIGSERIAL PRIMARY KEY,
                          name TEXT NOT NULL,
                          address TEXT,
                          contact TEXT
);

CREATE TABLE distributor (
                             id BIGSERIAL PRIMARY KEY,
                             name TEXT NOT NULL,
                             inn INTEGER,
                             contact TEXT
);

CREATE TABLE activity_apply (
                                id BIGSERIAL PRIMARY KEY,
                                tender_id BIGINT,
                                facility_id BIGINT,
                                items_json TEXT,
                                created_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE user_account (
                              id BIGSERIAL PRIMARY KEY,
                              username TEXT UNIQUE NOT NULL,
                              full_name TEXT,
                              role TEXT
);
