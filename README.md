# Riyaly (ريالي)

**Follow the money on your family's cards.** Riyaly is an Android app that reads your bank's SMS
messages, names the family member behind each transaction from the card's last four digits,
records spending and transfers, and alerts you to transactions worth a second look.

> **Testing version.** Riyaly currently understands **Al Rajhi Bank** messages best. Other banks
> can be added in Settings; please report messages that are read wrongly (see *Feedback* below).

## Download

Get the latest APK from **[Releases → latest](https://github.com/swaidi/Riyaly/releases/latest)**
(open *Assets* and tap the `.apk` file), or add the app to **Obtainium** with this URL:

```
https://github.com/swaidi/Riyaly
```

## Privacy

- Reads only messages from the bank senders you list (Al Rajhi Bank is preset).
- All data stays on your phone. There are no accounts, servers, ads or tracking.
- The internet is used only for optional features: the OpenStreetMap merchant lookup and map,
  and checking for app updates on this GitHub page.
- Backups are encrypted with your own password.

## Features

| Area | What it does |
|---|---|
| Family | Members with names and colors; their cards and accounts, each with a name, its own limits and travel mode |
| Messages | Purchases (online, in-store, international with fees and country), cash withdrawals, transfers in and out, refunds, declined transactions |
| Notifications | Name, amount and merchant for every transaction; high-priority alert with **Confirmed OK** / **Check with member** |
| Checks | Per-card purchase, withdrawal and monthly limits; large transfer out; new recipient; card not registered; unusual amount, merchant or time for that person; late-night purchase; several purchases within minutes; possible duplicate; foreign purchase; outside home city or travel destination; watch-list keyword; merchant far from home; declined |
| Dashboard | Spending, money in and out, by member and by card/account, monthly trend, top merchants, categories, transfer parties, day × hour heat map; tap any chart to see the transactions |
| History | Import the last 12 months of bank messages from the inbox |
| Backup | Encrypted backup file to a folder you choose; restore on a new phone |
| Report | Monthly Excel (.xlsx) report: summary, transactions, merchants |
| Languages | English and Arabic |

Card digits are searched in the whole message after amounts, balances, dates and times are
removed from a temporary copy, so an amount such as SR 1234 is never mistaken for card 1234.

## Setup

1. Install the APK.
2. If Android shows the SMS permission as restricted: **Settings → Apps → Riyaly → ⋮ → Allow restricted settings**.
3. Open Riyaly and allow SMS and notification access.
4. **Family** tab: add each member and the last four digits of his or her cards and accounts.
5. **Settings**: check the bank sender name, set your home location, watch list and backup password.
6. Optional: **Import from inbox** to build the history from earlier messages.

## Feedback

Please open an [issue](https://github.com/swaidi/Riyaly/issues) for problems or ideas. When a
message is read wrongly, paste the message with **names, amounts and card digits replaced**
(keep its wording and layout, which is what matters).

## Build

Every push to `main` runs `.github/workflows/release.yml`: it runs the message-reading tests,
builds a signed APK and publishes a release tagged `v1.0.<run number>`.
