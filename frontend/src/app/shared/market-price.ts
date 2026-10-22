/** Цены рынка по договорам goszakup (спека 2026-10-22-tender-results-market-prices §9, §10, §13). */
export interface MarketCategory { code: string; name: string | null; description: string | null; count: number; }

export interface MarketStats {
  count: number; median: number | null; p25: number | null; p75: number | null; min: number | null; max: number | null;
  budgetMedian: number | null; lotBudget: number | null; budgetDiffPct: number | null;
}

export interface MarketRow {
  signedAt: string | null; region: string | null; customer: string | null; quantity: number | null; unit: string | null;
  unitPrice: number | null; budgetUnitPrice: number | null; supplierName: string | null; anno: string | null; name: string | null;
}

export interface MarketPrice {
  category: MarketCategory | null; alternatives: MarketCategory[]; unit: string | null; units: string[]; stats: MarketStats;
  bids: { count: number; median: number | null }; rows: MarketRow[]; fetchedAt: string | null; stale: boolean; error: string | null;
}

export interface MarketLotSummary { text: string; count: number; median: number | null; code: string; }

export interface MarketHint { text: string; line: string | null; count: number; median: number | null; code: string | null; }

export interface MarketQuery { lotId?: number | null; q?: string | null; code?: string | null; unit?: string | null; filter?: string | null; }
