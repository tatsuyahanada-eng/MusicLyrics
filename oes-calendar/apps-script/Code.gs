/**
 * OES入替作業APP用 / 指定日の予定を返すウェブアプリ
 *
 * 導入手順
 *  1. https://script.google.com/ で新しいプロジェクトを作る
 *  2. このコードを貼り付けて保存
 *  3. 「デプロイ」→「新しいデプロイ」→ 種類は「ウェブアプリ」
 *  4. 次のユーザーとして実行: 自分
 *     アクセスできるユーザー: 全員
 *  5. 表示された /exec で終わるURLを、このツールの設定に貼り付ける
 */
function doGet(e) {
  var p = (e && e.parameter) ? e.parameter : {};
  var tz = "Asia/Tokyo";
  var dateStr = p.date || Utilities.formatDate(new Date(), tz, "yyyy-MM-dd");
  var a = dateStr.split("-");
  var start = new Date(Number(a[0]), Number(a[1]) - 1, Number(a[2]), 0, 0, 0);
  var end   = new Date(Number(a[0]), Number(a[1]) - 1, Number(a[2]), 23, 59, 59);

  var cal = p.calendar ? CalendarApp.getCalendarById(p.calendar)
                       : CalendarApp.getDefaultCalendar();
  var q = p.q || "";
  var events = [];
  cal.getEvents(start, end).forEach(function (ev) {
    var title = ev.getTitle() || "";
    if (q && title.indexOf(q) === -1) return;
    events.push({
      title: title,
      start: ev.isAllDayEvent() ? "" : Utilities.formatDate(ev.getStartTime(), tz, "HH:mm"),
      end:   ev.isAllDayEvent() ? "" : Utilities.formatDate(ev.getEndTime(),   tz, "HH:mm"),
      allDay: ev.isAllDayEvent(),
      location: ev.getLocation() || "",
      description: ev.getDescription() || ""
    });
  });

  return ContentService
    .createTextOutput(JSON.stringify({ date: dateStr, events: events }))
    .setMimeType(ContentService.MimeType.JSON);
}
