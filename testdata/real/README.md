# Your test photos

Synthetic photos are made from the same image the scanner compares against, so they
flatter it. Photos of real cards, taken with your phone the way you would at a flea
market, are the real test.

## How to add photos

1. Take photos with your phone: one card per photo. Mix easy and hard ones: sleeves,
   holo/glare, binder pages, bad light, a table with other stuff on it.
2. **Name each file after what is printed on the card:** its name (any language)
   and the number at the bottom. Write `4/102` as `4-102`, because `/` isn't allowed in
   file names.

   | card shows | file name |
   |---|---|
   | Glurak, 4/102 | `Glurak 4-102.jpg` |
   | Charizard ex, 006/165 | `Charizard ex 6-165.jpg` |
   | Arceus V, GG05/GG70 | `Arceus V GG05.jpg` |
   | you already know the TCGdex id | `base1-4.jpg` |
   | you don't know (just see what it says) | `unknown__1.jpg` |

   Anything after a double underscore is ignored, so you can photograph the same card
   several times: `Glurak 4-102__sleeve.jpg`, `Glurak 4-102__binder.jpg`.
3. Upload them on GitHub: open this folder, then **Add file → Upload files**, then **Commit**.
   The pipeline starts by itself, and the results show up on the run's Summary page.

Use JPG or PNG. If a label can't be matched to a card, the report shows ⚠️ and why.
Usually adding the total (`-102`) or the name fixes it.

Alternatively, list labels in `labels.csv` (`file,label` per line) instead of
renaming files.
