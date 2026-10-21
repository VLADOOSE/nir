package com.vladoose.nir.tgbot;

import com.vladoose.nir.entity.Distributor;
import com.vladoose.nir.service.aianswer.LetterRecipients;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Кому бот готовит письмо (спека 2026-10-21-tender-telegram-bot §2 п. 5): завод с почтой → «Доверенный» с почтой → никто. */
class LetterRecipientsTest {

    static Distributor d(String name, String email, boolean maker, boolean trusted, String... brands) {
        return Distributor.builder().name(name).email(email).manufacturer(maker).trusted(trusted)
                .brands(new ArrayList<>(List.of(brands))).build();
    }

    @Test
    void makerWithMailFirst_thenTrusted_neverPlainDealer() {
        Distributor maker = d("Аксион", "a@axion.ru", true, false, "Аксион");
        Distributor makerNoMail = d("Аксион-2", null, true, false, "Аксион");
        Distributor trusted = d("Дилер-Д", "t@x.kz", false, true, "Аксион");
        Distributor dealer = d("Дилер", "d@x.kz", false, false, "Аксион");
        assertThat(LetterRecipients.pick(List.of(dealer, trusted, maker), "Аксион")).containsExactly(maker);
        assertThat(LetterRecipients.pick(List.of(dealer, trusted, makerNoMail), "Аксион")).containsExactly(trusted);
        assertThat(LetterRecipients.pick(List.of(dealer), "Аксион")).isEmpty();
        assertThat(LetterRecipients.pick(List.of(maker), "не указан")).isEmpty();
        assertThat(LetterRecipients.pick(List.of(maker), null)).isEmpty();
    }
}
