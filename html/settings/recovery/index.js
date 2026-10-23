'use strict';

// Recovery: list each game's restore points and restore one (server: /Recovery/list and /Recovery/restore).

const recShown = 3; // restore points shown per game until "Show all"
const recExpanded = {};
let recLocal = false;

// Following the current game keeps the page connected (and refreshes the list when the current game changes).
WS.Register('ScoreBoard.CurrentGame.Game', function () {
  recLoad();
});

$(function () {
  recLoad();
  setInterval(recLoad, 15000);
});

function recLoad() {
  if ($('.Points tr.Confirm').length) {
    return; // don't redraw under a pending confirmation
  }
  $.getJSON('/Recovery/list', function (data) {
    recLocal = data.local;
    $('.NotLocal').toggleClass('sbHide', recLocal);
    recRender(data.games);
  }).fail(function () {
    $('.Status').text('Could not load the restore points.');
  });
}

function recClock(ms) {
  ms = Math.max(0, Number(ms) || 0);
  const s = Math.floor(ms / 1000);
  return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
}

function recSaved(ms) {
  const d = new Date(Number(ms));
  const today = new Date();
  const time = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  return d.toDateString() === today.toDateString() ? time : d.toLocaleDateString() + ' ' + time;
}

function recWhere(p) {
  const at = 'P' + p.period + ' ';
  switch (p.reason) {
    case 'jam-end':
      return p.inPeriod === false ? at + 'end of period (after jam ' + p.jam + ')' : at + 'end of jam ' + p.jam;
    case 'timeout-end':
      return at + 'end of timeout (after jam ' + p.jam + ')';
    case 'official-score':
      return 'Final score';
    case 'export':
      return at + 'statsbook update (jam ' + p.jam + ')';
    case 'restored':
      return at + 'restored here (jam ' + p.jam + ')';
    case 'abandoned':
      return at + 'set aside here (jam ' + p.jam + ')';
    default:
      return at + (p.inJam ? 'during jam ' : 'after jam ') + p.jam;
  }
}

function recSource(p) {
  if (p.source === 'autosave') {
    return 'autosave, ' + Math.max(0, Math.round((Date.now() - p.savedAt) / 1000)) + ' s ago';
  }
  if (p.source === 'backup') {
    return 'backup from a restart';
  }
  return 'restore point';
}

function recGameLine(g) {
  const live = g.live || (g.points && g.points[0]) || {};
  const notStarted = live.state === 'Prepared';
  const final = live.state === 'Finished' || live.officialScore;
  const where = notStarted ? 'Not started' : final ? 'Final' : 'P' + live.period + ' J' + live.jam;
  return $('<div class="Summary">')
    .append($('<span class="When">').text((g.date || '') + ' ' + (g.startTime || '').substring(0, 5)))
    .append($('<span class="Teams">').text((g.team1 || '?') + ' vs ' + (g.team2 || '?')))
    .append($('<span class="Where">').text(where))
    .append($('<span class="Score">').text(notStarted ? '' : live.score1 + ' - ' + live.score2))
    .append($('<span class="Clock">').text(notStarted || final ? '' : recClock(live.periodClock) + ' left'))
    .append(g.current ? $('<span class="Badge">').text('CURRENT') : null)
    .append(recAbandoned(g) ? $('<span class="Badge Abandoned">').text(recAbandoned(g)) : null);
}

function recAbandoned(g) {
  const m = /\(abandoned ([^)]*)\)/.exec(g.name || '');
  return m ? 'ABANDONED ' + m[1] : '';
}

function recRender(games) {
  const box = $('#Games').empty();
  if (!games.length) {
    box.append($('<p>').text('No games yet.'));
    return;
  }
  games.forEach(function (g) {
    const div = $('<div class="Game">').toggleClass('Current', !!g.current).appendTo(box);
    div.append(recGameLine(g));
    if (g.live && g.live.checkpointError) {
      div.append($('<p class="Warning More">').text(g.live.checkpointError));
    }
    const points = g.points || [];
    if (!points.length) {
      div.append($('<p class="More">').text('No restore points yet: the first is saved at the end of the first jam.'));
      return;
    }
    const table = $('<table class="Points">').appendTo(div);
    table.append(
      $('<tr>')
        .append($('<th>').text('Saved'))
        .append($('<th>').text('Where in the game'))
        .append($('<th>').text('Score'))
        .append($('<th>').text('Period clock'))
        .append($('<th>').text('From'))
        .append($('<th>'))
    );
    const shown = recExpanded[g.filename] ? points : recFirstFew(points);
    shown.forEach(function (p) {
      const actions = $('<td class="Actions">');
      if (p.source === 'checkpoint') {
        actions.append(
          $('<a download>')
            .attr('href', '/' + p.path.replace(/^html\//, ''))
            .text('Download')
            .button()
        );
      }
      $('<button>')
        .text('Restore...')
        .button({ disabled: !recLocal })
        .on('click', function () {
          recConfirm($(this).closest('tr'), g, p);
        })
        .appendTo(actions);
      table.append(
        $('<tr>')
          .append($('<td>').text(recSaved(p.savedAt)))
          .append($('<td>').text(recWhere(p)))
          .append($('<td>').text(p.score1 + ' - ' + p.score2))
          .append($('<td>').text(recClock(p.periodClock) + ' left'))
          .append($('<td>').text(recSource(p)))
          .append(actions)
      );
    });
    if (points.length > recShown) {
      $('<button class="More">')
        .text(recExpanded[g.filename] ? 'Show fewer' : 'Show all ' + points.length + ' restore points')
        .button()
        .on('click', function () {
          recExpanded[g.filename] = !recExpanded[g.filename];
          recRender(games);
        })
        .appendTo(div);
    }
  });
}

// The newest autosave (if it is newer than every restore point), then the newest restore points: autosaves come
// every 10 s and would otherwise fill the short list.
function recFirstFew(points) {
  const saved = points.filter(function (p) {
    return p.source !== 'autosave';
  });
  const out = [];
  if (points.length && points[0].source === 'autosave') {
    out.push(points[0]);
  }
  return out.concat(saved.slice(0, recShown - out.length));
}

function recConfirm(row, g, p) {
  $('.Points tr.Confirm').remove();
  const now = new Date();
  const label = String(now.getHours()).padStart(2, '0') + ':' + String(now.getMinutes()).padStart(2, '0');
  const inJam = g.live && g.live.inJam;
  const cell = $('<td colspan="6">')
    .append(inJam ? $('<p class="Warning">').text('A jam is running in this game right now.') : null)
    .append(
      $('<p>')
        .append('Restore "' + (g.team1 || '?') + ' vs ' + (g.team2 || '?') + '" to ')
        .append($('<b>').text(recWhere(p)))
        .append(' (' + p.score1 + ' - ' + p.score2 + ', ' + recClock(p.periodClock) + ' left)?')
    )
    .append(
      $('<ul>')
        .append($('<li>').text('A copy of the game from that point becomes the current game, with the same name and statsbook file.'))
        .append($('<li>').text('The game as it is now is kept, renamed "(abandoned ' + label + ')".'))
        .append($('<li>').append('Clocks will be as they were then: ').append($('<b>').text('check the period clock')).append(' before the next jam.'))
    );
  $('<button>')
    .text('Restore')
    .button()
    .on('click', function () {
      recRestore(p);
    })
    .appendTo(cell);
  $('<button>')
    .text('Cancel')
    .button()
    .on('click', function () {
      $(this).closest('tr').remove();
    })
    .appendTo(cell);
  row.after($('<tr class="Confirm">').append(cell));
}

function recRestore(p) {
  $('.Status').text('Restoring...');
  $.ajax({
    url: '/Recovery/restore?path=' + encodeURIComponent(p.path) + '&game=' + encodeURIComponent(p.game),
    method: 'POST',
  })
    .done(function () {
      $('.Status').text('Restored. The restored game is now the current game: check the period clock before the next jam.');
      $('.Points tr.Confirm').remove();
      recLoad();
    })
    .fail(function (xhr) {
      $('.Status').text('Restore failed: ' + (xhr.responseText || xhr.statusText));
    });
}
