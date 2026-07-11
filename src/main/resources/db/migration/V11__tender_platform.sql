-- Канал (площадка) тендера: GOSZAKUP / SK_PHARMACY. nullable → существующие KZ (null) фолбэк на goszakup.
ALTER TABLE tender ADD COLUMN platform VARCHAR(20);
