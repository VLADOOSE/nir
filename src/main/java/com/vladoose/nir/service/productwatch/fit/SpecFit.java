package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.entity.FitCandidate;
import com.vladoose.nir.entity.FitMark;
import com.vladoose.nir.entity.FitRow;
import com.vladoose.nir.entity.FitVerdict;

import java.util.*;

/**
 * Сверка требований ТЗ с нашим товаром (спека §6; чистая функция).
 * <p>
 * Калибровка задачи 14 плана 2026-10-20-product-spec-fit — набор {@code fit-golden.tsv} (37 настоящих ТЗ, гейт
 * {@code ProductFitQualityTest}): ложных «подходит» 0, найдено 2 из 4 (ДКИ-Н-12 по ТЗ, списанному с карточки РУ, и
 * ОМП-5/80 — РУ истекло). До калибровки ДКИ-Н-12 выходил «✗ не подходит» по одному слову «питании» в паре требования
 * с фактом. Пороги {@link #FITS_SHARE}, {@link #FITS_MIN_MET}, {@link #APPROX_TOLERANCE}, {@link #FEATURE_SHARE} не
 * двигались — ложные ✓ и ✗ убраны правилами: пара «требование — факт» по имени ({@link #NAME_MATCH}), без имени — только
 * сеть питания, кандидаты — товары того же вида с уточнениями группы, «количество» — не имя параметра, заголовок без
 * значения — не требование. Мутация {@code FITS_SHARE = 0.2} даёт три ложных «подходит»: ЭК3Т-01 на ТЗ 17719756-1,
 * ОУФНУ на облучатель 17822225-1, блок аспирации АСД на отсасыватель 17729514-1.
 */
public final class SpecFit {

    public static final double FITS_SHARE = 0.5;
    public static final int FITS_MIN_MET = 3;
    public static final double APPROX_TOLERANCE = 0.15;
    public static final double UPTO_TOLERANCE = 0.05;
    public static final double FEATURE_SHARE = 0.6;
    public static final int MIN_REQUIREMENTS = 2;
    public static final int MAX_ROWS = 80;
    private static final int MAX_ALTERNATIVES = 2;
    /**
     * Факт — тот же параметр, если совпала хотя бы половина значимых слов имени требования или имени факта. Калибровка
     * задачи 14: при «любом общем слове» ТЗ ДКИ-Н-12 (17733214-1) сверялось с «время непрерывной работы от сети» и
     * «график импульса … 175 Ом» — ложные ✗ и ✓ у нашего же товара.
     */
    public static final double NAME_MATCH = 0.5;
    /** «До X» у этих параметров — «не более X» (спека §5.2). */
    private static final List<String> SMALLER_BETTER = List.of("масса", "вес", "габари", "размер", "шум", "потреб");

    public static final Comparator<FitCandidate> RANKING = Comparator
            .comparingInt((FitCandidate c) -> -c.verdict().rank())
            .thenComparingInt(c -> -c.met())
            .thenComparingInt(FitCandidate::failed)
            .thenComparingInt(FitCandidate::unknown)
            .thenComparingInt(c -> c.name() == null ? 0 : c.name().length())
            .thenComparingLong(FitCandidate::productId);

    private SpecFit() {}

    public static LotFit evaluate(List<SpecRequirement> reqs, List<ProductCard> cards, String foreignBrand) {
        if (reqs.size() < MIN_REQUIREMENTS || cards.isEmpty()) return new LotFit(FitVerdict.NO_REQUIREMENTS, List.of(), foreignBrand);
        List<FitCandidate> all = cards.stream().map(c -> compare(reqs, c)).sorted(RANKING).toList();
        List<FitCandidate> out = new ArrayList<>();
        out.add(all.get(0));
        for (FitCandidate c : all.subList(1, all.size())) {
            if (out.size() > MAX_ALTERNATIVES) break;
            if (c.met() > 0) out.add(c);
        }
        return new LotFit(all.get(0).verdict(), List.copyOf(out), foreignBrand);
    }

    public static FitCandidate compare(List<SpecRequirement> reqs, ProductCard card) {
        List<FitRow> rows = new ArrayList<>();
        String reason = null, reasonShort = null;
        for (SpecRequirement r : reqs) {
            FitRow row;
            if (r.kind() == SpecRequirement.Kind.FEATURE) {
                boolean ok = FitText.overlap(FitText.stems(r.name()), card.stems()) >= FEATURE_SHARE;
                row = new FitRow(ok ? FitMark.OK : FitMark.UNKNOWN, r.text(), ok ? "есть в описании товара" : null, ok ? ProductCards.SITE : null);
            } else {
                ProductFact f = factFor(r, card.facts());
                FitMark m = f == null ? FitMark.UNKNOWN : check(r, f);
                row = new FitRow(m, r.text(), f == null ? null : cut(f.text(), 120), f == null ? null : f.source());
                if (m == FitMark.FAIL && reason == null) {
                    reason = cut(r.text() + " — у нас: " + f.text(), 300);
                    reasonShort = cut(r.name(), 40);
                }
            }
            rows.add(row);
        }
        int total = rows.size();
        int ok = (int) rows.stream().filter(x -> x.mark() == FitMark.OK).count();
        int fail = (int) rows.stream().filter(x -> x.mark() == FitMark.FAIL).count();
        int unknown = (int) rows.stream().filter(x -> x.mark() == FitMark.UNKNOWN).count();
        FitVerdict v;
        if (total < MIN_REQUIREMENTS) v = FitVerdict.NO_REQUIREMENTS;
        else if (fail > 0) v = FitVerdict.NOT_FIT;
        else if (ok >= FITS_MIN_MET && ok >= FITS_SHARE * total) v = card.regActive() ? FitVerdict.FITS : FitVerdict.FITS_NO_RU;
        else v = FitVerdict.PARTIAL;
        List<FitRow> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingInt(x -> order(x.mark())));
        return new FitCandidate(card.productId(), card.name(), card.slug(), v, ok, fail, total, unknown, reason, reasonShort,
                card.regStatus().name(), card.regNumber(), List.copyOf(sorted.subList(0, Math.min(MAX_ROWS, sorted.size()))));
    }

    private static int order(FitMark m) {
        return switch (m) { case FAIL -> 0; case DIFF -> 1; case OK -> 2; case UNKNOWN -> 3; };
    }

    /**
     * Факт для требования: та же единица (счётные — со счётными), совпадение имени не меньше {@link #NAME_MATCH}, из них —
     * наибольшее; без совпадения имени — только сеть питания (у прибора одно напряжение и одна частота сети): длина, масса,
     * мощность у товара бывают у разных частей («диаметр электрода» против «длина кабеля» — не сверка), а «частота
     * дискретизации 32 000 Гц» или «Li-ion 14,8 В» — не сеть.
     */
    static ProductFact factFor(SpecRequirement r, List<ProductFact> facts) {
        List<ProductFact> same = facts.stream().filter(f -> compatible(r, f)).toList();
        if (same.isEmpty()) return null;
        Set<String> rn = FitText.stems(r.name());
        ProductFact best = null;
        double bestScore = 0;
        for (ProductFact f : same) {
            Set<String> fn = FitText.stems(f.name());
            double a = FitText.overlap(rn, fn), b = FitText.overlap(fn, rn);
            if (Math.max(a, b) < NAME_MATCH) continue;
            double s = a + 0.5 * b;
            if (s > bestScore) { bestScore = s; best = f; }
        }
        if (best != null || !mains(r.unit(), r.lo(), r.hi())) return best;
        List<ProductFact> mains = same.stream().filter(f -> mains(f.unit(), f.lo(), f.hi())).toList();
        return mains.size() == 1 ? mains.get(0) : null;
    }

    /** Сеть питания: напряжение 100–400 В, частота 45–65 Гц (границы «0» и «бесконечность» — открытые, не в счёт). */
    private static boolean mains(SpecUnit u, double lo, double hi) {
        if (u == null) return false;
        double min, max;
        switch (u.family()) {
            case "напряжение" -> { min = 100; max = 400; }
            case "частота" -> { min = 45; max = 65; }
            default -> { return false; }
        }
        boolean any = false;
        for (double v : new double[]{lo, hi}) {
            if (v == 0 || Double.isInfinite(v)) continue;
            if (v < min || v > max) return false;
            any = true;
        }
        return any;
    }

    private static boolean compatible(SpecRequirement r, ProductFact f) {
        if (r.kind() == SpecRequirement.Kind.DIMS) return !f.dims().isEmpty();
        if (countLike(r.unit())) return countLike(f.unit()) && (r.unit() != SpecUnit.CHANNEL || f.unit() != SpecUnit.PCS);
        return f.unit() != null && f.dims().isEmpty() && f.unit().family().equals(r.unit().family());
    }

    private static boolean countLike(SpecUnit u) {
        return u == null || u == SpecUnit.CHANNEL || u == SpecUnit.PCS;
    }

    static FitMark check(SpecRequirement r, ProductFact f) {
        if (r.kind() == SpecRequirement.Kind.DIMS) return dims(r, f);
        boolean count = countLike(r.unit());
        boolean smaller = SMALLER_BETTER.stream().anyMatch(w -> r.name().contains(w));
        SpecCmp rc = r.cmp() == SpecCmp.UPTO && smaller ? SpecCmp.MAX : r.cmp();
        SpecCmp fc = f.cmp() == SpecCmp.UPTO ? SpecCmp.RANGE : f.cmp();
        double fLo = f.cmp() == SpecCmp.UPTO ? 0 : f.lo(), fHi = f.hi();
        switch (rc) {
            case MIN: case UPTO: {
                double x = rc == SpecCmp.MIN ? r.lo() : r.hi() * (1 - UPTO_TOLERANCE);
                return switch (fc) {
                    case MIN -> fLo >= x ? FitMark.OK : FitMark.UNKNOWN;
                    case MAX -> fHi < x ? FitMark.FAIL : FitMark.UNKNOWN;
                    default -> fHi >= x ? FitMark.OK : FitMark.FAIL;
                };
            }
            case MAX: {
                double x = r.hi();
                return switch (fc) {
                    case MAX -> fHi <= x ? FitMark.OK : FitMark.UNKNOWN;
                    case MIN -> fLo > x ? FitMark.FAIL : FitMark.UNKNOWN;
                    default -> fLo <= x ? FitMark.OK : FitMark.FAIL;
                };
            }
            case RANGE: {
                double a = r.lo(), b = r.hi();
                return switch (fc) {
                    case MIN -> fLo <= b ? FitMark.OK : FitMark.FAIL;
                    case MAX -> fHi >= a ? FitMark.OK : FitMark.FAIL;
                    default -> fLo <= b * (1 + 1e-9) && fHi >= a * (1 - 1e-9) ? FitMark.OK : FitMark.FAIL;
                };
            }
            default: {   // APPROX: число без условия
                double x = r.lo();
                if (count) return fc == SpecCmp.APPROX ? (Math.abs(fLo - x) < 1e-9 ? FitMark.OK : FitMark.FAIL) : FitMark.UNKNOWN;
                return switch (fc) {
                    case APPROX -> Math.abs(fLo - x) <= APPROX_TOLERANCE * Math.abs(x) ? FitMark.OK : FitMark.DIFF;
                    case RANGE -> x >= fLo && x <= fHi ? FitMark.OK : FitMark.DIFF;
                    case MIN -> Math.abs(fLo - x) <= APPROX_TOLERANCE * Math.abs(x) ? FitMark.OK : FitMark.UNKNOWN;
                    case MAX -> Math.abs(fHi - x) <= APPROX_TOLERANCE * Math.abs(x) ? FitMark.OK : FitMark.UNKNOWN;
                    default -> FitMark.UNKNOWN;
                };
            }
        }
    }

    /** Габариты — по осям по убыванию; оси сверх меньшего из двух списков не сравниваются. */
    private static FitMark dims(SpecRequirement r, ProductFact f) {
        int n = Math.min(r.dims().size(), f.dims().size());
        for (int i = 0; i < n; i++) {
            double want = r.dims().get(i), have = f.dims().get(i);
            boolean ok = switch (r.cmp()) {
                case MIN -> have >= want;
                case APPROX, RANGE -> Math.abs(have - want) <= APPROX_TOLERANCE * want;
                default -> have <= want;
            };
            if (!ok) return r.cmp() == SpecCmp.APPROX ? FitMark.DIFF : FitMark.FAIL;
        }
        return FitMark.OK;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
