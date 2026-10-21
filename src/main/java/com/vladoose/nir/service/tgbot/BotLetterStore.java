package com.vladoose.nir.service.tgbot;

import com.vladoose.nir.entity.BotLetter;
import com.vladoose.nir.entity.BotLetterItem;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.repository.BotLetterRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.service.aianswer.AiAnswerResult.LetterPlan;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Черновики писем из бота (спека 2026-10-21-tender-telegram-bot §7): запись — короткими транзакциями, сеть — снаружи. */
@Service
public class BotLetterStore {

    private final BotLetterRepository repo;
    private final TenderLotRepository lots;

    public BotLetterStore(BotLetterRepository repo, TenderLotRepository lots) {
        this.repo = repo;
        this.lots = lots;
    }

    /** По письму на план; позиции — модель в работе лота сейчас (её сверит «Отправить»). */
    @Transactional
    public List<BotLetter> createDrafts(List<LetterPlan> plans, String by) {
        List<BotLetter> out = new ArrayList<>();
        int n = 1;
        for (LetterPlan p : plans) {
            List<BotLetterItem> items = new ArrayList<>();
            for (Long lotId : p.lotIds()) {
                TenderLot l = lots.findById(lotId).orElseThrow();
                items.add(new BotLetterItem(l.getId(), l.getProposedEquipment() == null ? null : l.getProposedEquipment().getId(),
                        l.getQuantity()));
            }
            out.add(repo.save(BotLetter.builder().tenderId(p.tenderId()).distributorId(p.distributorId()).items(items)
                    .lineNo(n++).createdBy(by).build()));
        }
        return out;
    }

    @Transactional
    public void attachList(List<Long> ids, long listMessageId) {
        repo.findAllById(ids).forEach(b -> b.setListMessageId(listMessageId));
    }

    public Optional<BotLetter> find(long id) { return repo.findById(id); }

    public List<BotLetter> list(Long listMessageId) {
        return listMessageId == null ? List.of() : repo.findByListMessageIdOrderByLineNo(listMessageId);
    }
}
