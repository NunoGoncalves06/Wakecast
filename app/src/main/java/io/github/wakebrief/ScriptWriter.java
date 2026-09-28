package io.github.wakebrief;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Turns weather, agenda, to-dos and headlines into a spoken script with a personality.
 * Plain templates, no AI: every line below is what gets read out. Each persona has its own
 * wording for every line (not just the greeting), so the butler never sounds like the sergeant.
 * Variants are picked at random so mornings don't all sound the same.
 */
final class ScriptWriter {

    static final Locale PT = Locale.forLanguageTag("pt-PT");

    static final String[] PERSONA_KEYS = {"butler", "sergeant", "radio", "pirate", "zen"};

    static String personaName(String key) {
        switch (key) {
            case "sergeant": return "Drill Sergeant";
            case "radio": return "Radio Host";
            case "pirate": return "Pirate Captain";
            case "zen": return "Zen Guide";
            default: return "The Butler";
        }
    }

    static String emoji(String key) {
        switch (key) {
            case "sergeant": return "🪖";
            case "radio": return "📻";
            case "pirate": return "🏴‍☠️";
            case "zen": return "🧘";
            default: return "🎩";
        }
    }

    static String tagline(String key) {
        switch (key) {
            case "sergeant": return "Loud, fast, gets you out of bed";
            case "radio": return "Upbeat morning-show energy";
            case "pirate": return "Arr. Salty sea-dog talk";
            case "zen": return "Slow, calm, gentle start";
            default: return "Formal, British, dry wit";
        }
    }

    /** One greeting in the persona's voice, for the "hear a sample" button. */
    static String sample(String lang, String persona, String name) {
        ScriptWriter w = new ScriptWriter(lang, persona, new Random());
        w.initVars(name, System.currentTimeMillis());
        return w.pick("greet");
    }

    /**
     * Speaking speed. Personas differ only a little: pitch is never changed, because shifting it
     * is what made the voices sound metallic and robotic. The character is in the words.
     */
    static float rate(String persona) {
        switch (persona) {
            case "sergeant": return 1.08f;
            case "radio": return 1.05f;
            case "pirate": return 0.96f;
            case "zen": return 0.9f;
            default: return 0.97f;
        }
    }

    /** Voice locale. The butler gets a British voice in English. */
    static Locale locale(String lang, String persona) {
        if ("pt".equals(lang)) return PT;
        return "butler".equals(persona) ? Locale.UK : Locale.US;
    }

    /** How the persona addresses you when no name is set. */
    private static String defaultAddress(String persona, boolean pt) {
        switch (persona) {
            case "sergeant": return pt ? "recruta" : "recruit";
            case "radio": return pt ? "caro ouvinte" : "sleepyhead";
            case "pirate": return pt ? "comandante" : "captain";
            case "zen": return pt ? "alma serena" : "friend";
            default: return pt ? "Vossa Excelência" : "Your Excellency";
        }
    }

    static final class Data {
        // Filled in from several download threads, hence volatile.
        volatile WeatherClient.Weather weather;       // null = unavailable
        volatile List<AgendaReader.Event> agenda;     // null = unavailable
        volatile boolean noCalendarPermission;
        volatile List<String> news;                   // null = all feeds failed
    }

    static final class Line {
        final String text;
        final int pauseAfterMs;
        /** "en" or "pt": the voice to read it with. Headlines can differ from the chosen language. */
        final String lang;

        Line(String text, int pauseAfterMs, String lang) {
            this.text = text;
            this.pauseAfterMs = pauseAfterMs;
            this.lang = lang;
        }
    }

    // ------------------------------------------------------------------ persona lines
    //
    // Placeholders: {name} {time} {day} {count} {temp} {feels} {sky} {max} {min} {chance}
    // {at} {from} {uv} {wind} {title} {place} {n} {in}.
    // English {at} is a bare time ("7:30 AM"). Portuguese {at} carries its preposition
    // ("às 7 e 30", "ao meio-dia") and {from} is "a partir das 7 e 30", so the templates
    // never have to guess between às / ao / à.

    private static final Map<String, String[]> T = new HashMap<>();

    private static void put(String key, String... variants) {
        T.put(key, variants);
    }

    static {
        // ======================================================== The Butler (formal, dry)
        put("butler.en.greet",
                "Good morning, {name}. It is {time} on {day}. I trust you slept well. Allow me to brief you on the day ahead.",
                "Ahem. Good morning, {name}. The hour is {time}, {day}. Your morning report is ready, as always.");
        put("butler.en.weather", "First, the weather.", "Regarding the weather, {name}.");
        put("butler.en.now",
                "At present it is {temp} degrees, with {sky}.",
                "The thermometer outside reads {temp} degrees, and we have {sky}.");
        put("butler.en.feels", "Though I must say, it feels rather more like {feels}.");
        put("butler.en.today", "For the day, one anticipates {sky}, rising to {max} and falling to {min} degrees.");
        put("butler.en.rainAt", "I regret to report a {chance} percent chance of rain, most likely from around {at}.");
        put("butler.en.rain", "I regret to report a {chance} percent chance of rain today.");
        put("butler.en.rainLow", "A modest {chance} percent chance of rain. Hardly worth mentioning, but there it is.");
        put("butler.en.dry", "No rain is expected. Most agreeable.");
        put("butler.en.umbrella", "I have taken the liberty of suggesting an umbrella.");
        put("butler.en.cold", "It is decidedly cold. Your warmest coat, I should think.");
        put("butler.en.jacket", "A jacket would be prudent.");
        put("butler.en.hot", "It promises to be hot. Do remember to take water.");
        put("butler.en.uv", "The UV index reaches {uv}. Sunscreen would be advisable.");
        put("butler.en.wind", "Expect a stiff breeze, up to {wind} kilometres per hour. Mind your hat.");
        put("butler.en.sunset", "Sunset this evening is at {at}.");
        put("butler.en.agenda",
                "As for your engagements, you have {count} today.",
                "Your diary for today holds {count}.");
        put("butler.en.empty", "Your diary is entirely clear today. A rare luxury, {name}.");
        put("butler.en.allDay", "All day: {title}.");
        put("butler.en.event", "At {at}, {title}.");
        put("butler.en.eventAt", "At {at}, {title}, at {place}.");
        put("butler.en.more", "And {n} more besides.");
        put("butler.en.first", "Your first engagement is in {in}. I would not dawdle.");
        put("butler.en.todo", "You also asked me to remind you of the following.");
        put("butler.en.news",
                "And now, a selection of the morning's headlines.",
                "The morning papers report the following.");
        put("butler.en.outro",
                "That concludes the briefing. Do have a splendid day, {name}.",
                "I shall leave you to it. Your day awaits, {name}.");
        put("butler.en.noCity", "I am unable to report on the weather until you tell me where you are. The city can be set in the app.");
        put("butler.en.noWeather", "I regret that the weather service was unavailable this morning.");
        put("butler.en.noCalendar", "I have not been granted access to your diary. You may allow it in the app.");
        put("butler.en.noNews", "The morning papers failed to arrive, I'm afraid.");

        put("butler.pt.greet",
                "Bom dia, {name}. São {time}. Hoje é {day}. Espero que tenha dormido bem. Permita-me apresentar-lhe o dia.",
                "Ahem. Bom dia, {name}. São {time}, {day}. O seu relatório matinal está pronto, como sempre.");
        put("butler.pt.weather", "Em primeiro lugar, o tempo.", "Quanto à meteorologia, {name}.");
        put("butler.pt.now",
                "Neste momento estão {temp} graus, com {sky}.",
                "O termómetro marca {temp} graus, e temos {sky}.");
        put("butler.pt.feels", "Se me permite, a sensação é antes de {feels} graus.");
        put("butler.pt.today", "Para o dia de hoje, prevê-se {sky}, com máxima de {max} e mínima de {min} graus.");
        put("butler.pt.rainAt", "Lamento informar que há {chance} por cento de probabilidade de chuva, sobretudo {from}.");
        put("butler.pt.rain", "Lamento informar que há {chance} por cento de probabilidade de chuva hoje.");
        put("butler.pt.rainLow", "Uma modesta probabilidade de chuva, de {chance} por cento. Pouco digna de nota.");
        put("butler.pt.dry", "Não se espera chuva. Muito agradável.");
        put("butler.pt.umbrella", "Tomei a liberdade de lhe sugerir o guarda-chuva.");
        put("butler.pt.cold", "Está decididamente frio. O seu casaco mais quente, diria eu.");
        put("butler.pt.jacket", "Um casaco seria prudente.");
        put("butler.pt.hot", "Promete estar calor. Não se esqueça de levar água.");
        put("butler.pt.uv", "O índice UV chega a {uv}. Recomendaria protetor solar.");
        put("butler.pt.wind", "Espera-se vento forte, até {wind} quilómetros por hora. Cuidado com o chapéu.");
        put("butler.pt.sunset", "O pôr do sol será {at}.");
        put("butler.pt.agenda",
                "Quanto aos seus compromissos, tem {count} hoje.",
                "A sua agenda de hoje contém {count}.");
        put("butler.pt.empty", "A sua agenda está completamente livre hoje. Um raro luxo, {name}.");
        put("butler.pt.allDay", "Todo o dia: {title}.");
        put("butler.pt.event", "{at}, {title}.");
        put("butler.pt.eventAt", "{at}, {title}, em {place}.");
        put("butler.pt.more", "E mais {n}, além destes.");
        put("butler.pt.first", "O seu primeiro compromisso é daqui a {in}. Não me demoraria.");
        put("butler.pt.todo", "Pediu-me também que lhe recordasse o seguinte.");
        put("butler.pt.news",
                "E agora, uma seleção das notícias desta manhã.",
                "Os jornais desta manhã relatam o seguinte.");
        put("butler.pt.outro",
                "Está concluído o relatório. Tenha um dia esplêndido, {name}.",
                "Com isto, termino. O seu dia aguarda, {name}.");
        put("butler.pt.noCity", "Não posso informá-lo sobre o tempo sem saber onde se encontra. Pode definir a cidade na aplicação.");
        put("butler.pt.noWeather", "Lamento, mas o serviço meteorológico não respondeu esta manhã.");
        put("butler.pt.noCalendar", "Ainda não me foi concedido acesso à sua agenda. Pode autorizá-lo na aplicação.");
        put("butler.pt.noNews", "Receio que os jornais desta manhã não tenham chegado.");

        // ======================================================== Drill Sergeant (orders)
        put("sergeant.en.greet",
                "Rise and shine, {name}! It is {time}, {day}! Feet on the floor, now!",
                "Wakey wakey, {name}! {time}, {day}! This day will not conquer itself!");
        put("sergeant.en.weather", "Weather report! Listen up!", "Conditions on the battlefield today!");
        put("sergeant.en.now",
                "Current temperature: {temp} degrees! Conditions: {sky}!",
                "Outside it is {temp} degrees and {sky}! No excuses!");
        put("sergeant.en.feels", "Feels like {feels}! Deal with it!");
        put("sergeant.en.today", "Today: {sky}! High {max}, low {min}! Plan accordingly!");
        put("sergeant.en.rainAt", "Rain probability {chance} percent, moving in around {at}!");
        put("sergeant.en.rain", "Rain probability {chance} percent! You have been warned!");
        put("sergeant.en.rainLow", "Rain risk {chance} percent! Low, but stay alert!");
        put("sergeant.en.dry", "No rain! Zero excuses!");
        put("sergeant.en.umbrella", "Umbrella! Pack it! That is an order!");
        put("sergeant.en.cold", "It is cold out there! Heavy coat! Now!");
        put("sergeant.en.jacket", "Jacket on, soldier!");
        put("sergeant.en.hot", "It will be hot! Hydrate! That is an order!");
        put("sergeant.en.uv", "UV index {uv}! Sunscreen! No exceptions!");
        put("sergeant.en.wind", "Winds up to {wind} kilometres per hour! Hold your ground!");
        put("sergeant.en.sunset", "Sunset at {at}! Get it done before dark!");
        put("sergeant.en.agenda", "Your mission today has {count}!", "Orders for today: {count}!");
        put("sergeant.en.empty", "No missions on the calendar. Do not mistake that for a day off, {name}!");
        put("sergeant.en.allDay", "All day: {title}!");
        put("sergeant.en.event", "{at}: {title}!");
        put("sergeant.en.eventAt", "{at}: {title}! Location: {place}!");
        put("sergeant.en.more", "Plus {n} more! Stay sharp!");
        put("sergeant.en.first", "First mission starts in {in}! The clock is ticking!");
        put("sergeant.en.todo", "And your personal orders!");
        put("sergeant.en.news",
                "Intel from the outside world!",
                "Headlines! Pay attention, there will be a test!");
        put("sergeant.en.outro",
                "Briefing complete! Now move, move, move!",
                "Dismissed! Go make it count, {name}!");
        put("sergeant.en.noCity", "I don't know where you're stationed! Set your city in the app!");
        put("sergeant.en.noWeather", "Weather intel is down this morning! Improvise!");
        put("sergeant.en.noCalendar", "No access to your calendar! Grant it in the app! Now!");
        put("sergeant.en.noNews", "No intel from the news feeds this morning!");

        put("sergeant.pt.greet",
                "De pé, {name}! São {time}! Hoje é {day}! Pés no chão, já!",
                "Alvorada, {name}! São {time}, {day}! O dia não se conquista sozinho!");
        put("sergeant.pt.weather", "Boletim meteorológico! Atenção!", "Condições no terreno hoje!");
        put("sergeant.pt.now",
                "Temperatura atual: {temp} graus! Condições: {sky}!",
                "Lá fora estão {temp} graus e {sky}! Sem desculpas!");
        put("sergeant.pt.feels", "Sensação de {feels} graus! Aguenta!");
        put("sergeant.pt.today", "Hoje: {sky}! Máxima {max}, mínima {min}! Planeia em conformidade!");
        put("sergeant.pt.rainAt", "Probabilidade de chuva: {chance} por cento, {from}!");
        put("sergeant.pt.rain", "Probabilidade de chuva: {chance} por cento! Estás avisado!");
        put("sergeant.pt.rainLow", "Risco de chuva: {chance} por cento! Baixo, mas atento!");
        put("sergeant.pt.dry", "Sem chuva! Zero desculpas!");
        put("sergeant.pt.umbrella", "Guarda-chuva! Leva-o! É uma ordem!");
        put("sergeant.pt.cold", "Está frio lá fora! Casaco grosso! Já!");
        put("sergeant.pt.jacket", "Casaco vestido, soldado!");
        put("sergeant.pt.hot", "Vai estar calor! Hidrata-te! É uma ordem!");
        put("sergeant.pt.uv", "Índice UV {uv}! Protetor solar! Sem exceções!");
        put("sergeant.pt.wind", "Vento até {wind} quilómetros por hora! Mantém a posição!");
        put("sergeant.pt.sunset", "Pôr do sol {at}! Despacha tudo antes de escurecer!");
        put("sergeant.pt.agenda", "A tua missão de hoje tem {count}!", "Ordens para hoje: {count}!");
        put("sergeant.pt.empty", "Nenhuma missão na agenda. Isso não é um dia de folga, {name}!");
        put("sergeant.pt.allDay", "Todo o dia: {title}!");
        put("sergeant.pt.event", "{at}: {title}!");
        put("sergeant.pt.eventAt", "{at}: {title}! Local: {place}!");
        put("sergeant.pt.more", "E mais {n}! Atenção!");
        put("sergeant.pt.first", "Primeira missão daqui a {in}! O relógio não para!");
        put("sergeant.pt.todo", "E as tuas ordens pessoais!");
        put("sergeant.pt.news",
                "Informações do mundo exterior!",
                "Notícias! Presta atenção, vai haver teste!");
        put("sergeant.pt.outro",
                "Relatório concluído! Agora mexe-te, mexe-te, mexe-te!",
                "Destroçar! Vai lá fazer valer o dia, {name}!");
        put("sergeant.pt.noCity", "Não sei onde estás destacado! Define a tua cidade na aplicação!");
        put("sergeant.pt.noWeather", "Sem informações meteorológicas esta manhã! Improvisa!");
        put("sergeant.pt.noCalendar", "Sem acesso ao teu calendário! Autoriza na aplicação! Já!");
        put("sergeant.pt.noNews", "Sem informações das notícias esta manhã!");

        // ======================================================== Radio Host (morning show)
        put("radio.en.greet",
                "Goooood morning, {name}! You're tuned in to Wake Radio, and it's {time} on this fine {day}!",
                "Hello hello hello! It's {time}, it's {day}, and you're listening to the one and only morning show!");
        put("radio.en.weather", "Let's check in with the weather desk!", "Time for the forecast!");
        put("radio.en.now",
                "Right now it's {temp} degrees out there with {sky}!",
                "Checking the thermometer: {temp} degrees and {sky}!");
        put("radio.en.feels", "But it feels more like {feels}, folks!");
        put("radio.en.today", "Today's forecast: {sky}, topping out at {max} and dipping to {min}!");
        put("radio.en.rainAt", "Heads up, {chance} percent chance of rain, rolling in around {at}!");
        put("radio.en.rain", "Heads up, {chance} percent chance of rain today!");
        put("radio.en.rainLow", "Just a {chance} percent chance of rain, so fingers crossed!");
        put("radio.en.dry", "And no rain in sight! Love to see it!");
        put("radio.en.umbrella", "Pro tip: grab that umbrella on your way out!");
        put("radio.en.cold", "Brr! Bundle up, it's a cold one!");
        put("radio.en.jacket", "Don't forget a jacket!");
        put("radio.en.hot", "It's a scorcher! Keep that water bottle close!");
        put("radio.en.uv", "UV index hitting {uv} today, so slap on some sunscreen!");
        put("radio.en.wind", "Hold onto your hats, gusts up to {wind} kilometres per hour!");
        put("radio.en.sunset", "And sunset tonight is at {at}!");
        put("radio.en.agenda",
                "Let's see what's on your playlist today: {count}.",
                "Your lineup today features {count}.");
        put("radio.en.empty", "Your calendar is wide open today, so you're the DJ now!");
        put("radio.en.allDay", "All day long: {title}!");
        put("radio.en.event", "At {at}, it's {title}!");
        put("radio.en.eventAt", "At {at}, it's {title}, over at {place}!");
        put("radio.en.more", "Plus {n} more on the playlist!");
        put("radio.en.first", "First up on your playlist starts in {in}!");
        put("radio.en.todo", "And a few requests from our listener, that's you!");
        put("radio.en.news", "And now, the headlines making noise this morning!", "Over to the newsroom!");
        put("radio.en.outro",
                "That's the show! Stay tuned to your own life, {name}, and have a great one!",
                "This has been your morning briefing. Now go out there and crush it!");
        put("radio.en.noCity", "Tell me where you're listening from! Set your city in the app and I'll bring you the forecast!");
        put("radio.en.noWeather", "Our weather desk is off the air this morning!");
        put("radio.en.noCalendar", "I can't peek at your calendar yet! Give me access in the app!");
        put("radio.en.noNews", "The newsroom went quiet this morning, no headlines came through!");

        put("radio.pt.greet",
                "Muito bom dia, {name}! Estás a ouvir a Rádio Despertar, são {time}, {day}!",
                "Olá olá olá! São {time}, hoje é {day}, e estás a ouvir o melhor programa da manhã!");
        put("radio.pt.weather", "Vamos ao boletim do tempo!", "Hora da previsão!");
        put("radio.pt.now",
                "Neste momento estão {temp} graus lá fora, com {sky}!",
                "Espreitamos o termómetro: {temp} graus e {sky}!");
        put("radio.pt.feels", "Mas a sensação é mais de {feels} graus, pessoal!");
        put("radio.pt.today", "A previsão para hoje: {sky}, a subir até aos {max} e a descer aos {min} graus!");
        put("radio.pt.rainAt", "Atenção, {chance} por cento de probabilidade de chuva, a chegar {from}!");
        put("radio.pt.rain", "Atenção, {chance} por cento de probabilidade de chuva hoje!");
        put("radio.pt.rainLow", "Só {chance} por cento de probabilidade de chuva, dedos cruzados!");
        put("radio.pt.dry", "E nada de chuva à vista! Assim é que é!");
        put("radio.pt.umbrella", "Dica da casa: leva o guarda-chuva à saída!");
        put("radio.pt.cold", "Brrr! Agasalha-te bem, que hoje está frio!");
        put("radio.pt.jacket", "Não te esqueças do casaco!");
        put("radio.pt.hot", "Hoje vai ser um escaldão! Garrafa de água sempre à mão!");
        put("radio.pt.uv", "O índice UV chega a {uv} hoje, por isso protetor solar!");
        put("radio.pt.wind", "Segura o chapéu, rajadas até {wind} quilómetros por hora!");
        put("radio.pt.sunset", "E o pôr do sol hoje é {at}!");
        put("radio.pt.agenda",
                "Vamos ver a tua playlist de hoje: {count}.",
                "O alinhamento de hoje tem {count}.");
        put("radio.pt.empty", "A tua agenda está livre hoje, por isso hoje és tu o DJ!");
        put("radio.pt.allDay", "O dia todo: {title}!");
        put("radio.pt.event", "{at}, temos {title}!");
        put("radio.pt.eventAt", "{at}, temos {title}, em {place}!");
        put("radio.pt.more", "E mais {n} no alinhamento!");
        put("radio.pt.first", "O primeiro da lista começa daqui a {in}!");
        put("radio.pt.todo", "E alguns pedidos do nosso ouvinte, que és tu!");
        put("radio.pt.news", "E agora, as notícias que estão a dar que falar!", "Passamos à redação!");
        put("radio.pt.outro",
                "E assim termina o programa! Tem um ótimo dia, {name}!",
                "Este foi o teu resumo da manhã. Agora vai lá arrasar!");
        put("radio.pt.noCity", "Diz-me de onde estás a ouvir! Define a tua cidade na aplicação e eu trago a previsão!");
        put("radio.pt.noWeather", "O nosso boletim do tempo está fora do ar esta manhã!");
        put("radio.pt.noCalendar", "Ainda não consigo espreitar a tua agenda! Dá-me acesso na aplicação!");
        put("radio.pt.noNews", "A redação ficou em silêncio esta manhã, não chegaram notícias!");

        // ======================================================== Pirate Captain (nautical)
        put("pirate.en.greet",
                "Ahoy, {name}! It be {time} on {day}. Hoist yerself out of that hammock!",
                "Arr, wake up, {name}! The sun be up, and it be {time} on {day}.");
        put("pirate.en.weather", "The skies and the seas today.", "Here be the weather, matey.");
        put("pirate.en.now",
                "The air be {temp} degrees, with {sky} overhead.",
                "Me spyglass says {temp} degrees and {sky}, matey.");
        put("pirate.en.feels", "Though it bites more like {feels}, arr.");
        put("pirate.en.today", "The day brings {sky}, up to {max} and down to {min} degrees.");
        put("pirate.en.rainAt", "There be a {chance} percent chance o' rain, blowin' in around {at}.");
        put("pirate.en.rain", "There be a {chance} percent chance o' rain today, so batten down the hatches.");
        put("pirate.en.rainLow", "Only a {chance} percent chance o' rain. Calm waters, mostly.");
        put("pirate.en.dry", "No rain on the horizon. Smooth sailin'!");
        put("pirate.en.umbrella", "Take yer umbrella, or ye'll be soaked like a drowned rat.");
        put("pirate.en.cold", "It be cold enough to freeze a mermaid's tail. Wrap up warm!");
        put("pirate.en.jacket", "Grab yer coat, matey.");
        put("pirate.en.hot", "It'll be hot as a cannon's mouth. Drink plenty o' water, not rum!");
        put("pirate.en.uv", "The sun be fierce, UV index {uv}. Protect yer hide with sunscreen.");
        put("pirate.en.wind", "Strong winds, up to {wind} kilometres per hour. Good for sailin', bad for hats!");
        put("pirate.en.sunset", "The sun sinks below the horizon at {at}.");
        put("pirate.en.agenda", "Yer voyage today has {count} on the map.", "Aboard the ship today: {count}.");
        put("pirate.en.empty", "No ports to call on today. The seas be yours, {name}!");
        put("pirate.en.allDay", "All day long: {title}.");
        put("pirate.en.event", "At {at}, {title}.");
        put("pirate.en.eventAt", "At {at}, {title}, at the port of {place}.");
        put("pirate.en.more", "And {n} more on the map.");
        put("pirate.en.first", "Yer first port o' call be in {in}.");
        put("pirate.en.todo", "And don't forget yer chores, landlubber.");
        put("pirate.en.news", "News from across the seven seas.", "Word from the ports this morning.");
        put("pirate.en.outro",
                "That be all. Now weigh anchor and set sail, {name}!",
                "Fair winds today, {name}. Arr!");
        put("pirate.en.noCity", "I don't know which port ye be anchored in! Set yer city in the app.");
        put("pirate.en.noWeather", "Me weather spyglass be broken this mornin'. Couldn't reach the forecast.");
        put("pirate.en.noCalendar", "Yer calendar be locked in a chest. Grant me access in the app.");
        put("pirate.en.noNews", "No messages in bottles washed up this mornin'. The news didn't arrive.");

        put("pirate.pt.greet",
                "Ahoy, {name}! São {time}. Hoje é {day}. Sai dessa rede e sobe ao convés!",
                "Arr, acorda, {name}! O sol já nasceu, são {time}, {day}.");
        put("pirate.pt.weather", "Os céus e os mares de hoje.", "Aqui vai o tempo, grumete.");
        put("pirate.pt.now",
                "O ar está a {temp} graus, com {sky} lá em cima.",
                "A minha luneta diz {temp} graus e {sky}, grumete.");
        put("pirate.pt.feels", "Mas morde mais como {feels} graus, arr.");
        put("pirate.pt.today", "O dia traz {sky}, até {max} e no mínimo {min} graus.");
        put("pirate.pt.rainAt", "Há {chance} por cento de probabilidade de chuva, a chegar {from}.");
        put("pirate.pt.rain", "Há {chance} por cento de probabilidade de chuva hoje. Fecha as escotilhas!");
        put("pirate.pt.rainLow", "Só {chance} por cento de probabilidade de chuva. Águas calmas, no geral.");
        put("pirate.pt.dry", "Nenhuma chuva no horizonte. Navegação tranquila!");
        put("pirate.pt.umbrella", "Leva o guarda-chuva, ou ficas encharcado como um rato de porão.");
        put("pirate.pt.cold", "Está frio que chegue para gelar a cauda de uma sereia. Agasalha-te!");
        put("pirate.pt.jacket", "Pega no casaco, grumete.");
        put("pirate.pt.hot", "Vai estar quente como a boca de um canhão. Bebe muita água, e não rum!");
        put("pirate.pt.uv", "O sol está feroz, índice UV {uv}. Protege a pele com protetor solar.");
        put("pirate.pt.wind", "Vento forte, até {wind} quilómetros por hora. Bom para navegar, mau para chapéus!");
        put("pirate.pt.sunset", "O sol afunda-se no horizonte {at}.");
        put("pirate.pt.agenda", "A viagem de hoje tem {count} no mapa.", "A bordo hoje: {count}.");
        put("pirate.pt.empty", "Nenhum porto para visitar hoje. Os mares são teus, {name}!");
        put("pirate.pt.allDay", "O dia inteiro: {title}.");
        put("pirate.pt.event", "{at}, {title}.");
        put("pirate.pt.eventAt", "{at}, {title}, no porto de {place}.");
        put("pirate.pt.more", "E mais {n} no mapa.");
        put("pirate.pt.first", "O teu primeiro porto de escala é daqui a {in}.");
        put("pirate.pt.todo", "E não te esqueças das tarefas a bordo.");
        put("pirate.pt.news", "Notícias dos sete mares.", "Novidades dos portos esta manhã.");
        put("pirate.pt.outro",
                "É tudo. Agora levanta âncora e iça as velas, {name}!",
                "Bons ventos hoje, {name}. Arr!");
        put("pirate.pt.noCity", "Não sei em que porto estás ancorado! Define a tua cidade na aplicação.");
        put("pirate.pt.noWeather", "A minha luneta do tempo avariou esta manhã. Não consegui a previsão.");
        put("pirate.pt.noCalendar", "A tua agenda está fechada num baú. Dá-me acesso na aplicação.");
        put("pirate.pt.noNews", "Nenhuma mensagem numa garrafa chegou esta manhã. As notícias não vieram.");

        // ======================================================== Zen Guide (calm, gentle)
        put("zen.en.greet",
                "Good morning, {name}. Take a slow, deep breath. It is {time}, {day}.",
                "Hello, {name}. A new day begins. It is {time} on {day}.");
        put("zen.en.weather", "Let's see what the sky has in store.", "Outside, the weather.");
        put("zen.en.now",
                "Right now, it is {temp} degrees, with {sky}.",
                "Outside, {sky}, and {temp} degrees.");
        put("zen.en.feels", "Your skin may feel it closer to {feels}.");
        put("zen.en.today", "The day will bring {sky}, between {min} and {max} degrees.");
        put("zen.en.rainAt", "Rain may come, a {chance} percent chance, perhaps from around {at}.");
        put("zen.en.rain", "There is a {chance} percent chance of rain. Let it be.");
        put("zen.en.rainLow", "Only a small chance of rain, {chance} percent.");
        put("zen.en.dry", "No rain today. A dry, open sky.");
        put("zen.en.umbrella", "Perhaps bring an umbrella, just in case.");
        put("zen.en.cold", "It is cold. Wrap yourself in something warm.");
        put("zen.en.jacket", "A light jacket will keep you comfortable.");
        put("zen.en.hot", "It will be warm. Drink water, and find some shade.");
        put("zen.en.uv", "The sun is strong, UV index {uv}. Care for your skin.");
        put("zen.en.wind", "The wind will be strong, up to {wind} kilometres per hour. Move with it.");
        put("zen.en.sunset", "The sun sets at {at}.");
        put("zen.en.agenda", "Today you have {count}.", "Your day holds {count}.");
        put("zen.en.empty", "Your calendar is empty today. Space to breathe.");
        put("zen.en.allDay", "All day, {title}.");
        put("zen.en.event", "At {at}, {title}.");
        put("zen.en.eventAt", "At {at}, {title}, at {place}.");
        put("zen.en.more", "And {n} more. One thing at a time.");
        put("zen.en.first", "Your first moment of the day begins in {in}. There is time.");
        put("zen.en.todo", "Things you wanted to remember.");
        put("zen.en.news", "A few things happening in the world.", "Some news, to hold lightly.");
        put("zen.en.outro",
                "That's all. Move gently into your day, {name}.",
                "Be well today, {name}.");
        put("zen.en.noCity", "I don't know where you are yet. When you're ready, set your city in the app.");
        put("zen.en.noWeather", "The weather was out of reach this morning. That's alright.");
        put("zen.en.noCalendar", "I can't see your calendar yet. You can allow it in the app.");
        put("zen.en.noNews", "No news came through this morning. Perhaps that is a gift.");

        put("zen.pt.greet",
                "Bom dia, {name}. Respira fundo, devagar. São {time}. Hoje é {day}.",
                "Olá, {name}. Começa um novo dia. São {time}, {day}.");
        put("zen.pt.weather", "Vejamos o que o céu nos reserva.", "Lá fora, o tempo.");
        put("zen.pt.now",
                "Neste momento, estão {temp} graus, com {sky}.",
                "Lá fora, {sky}, e {temp} graus.");
        put("zen.pt.feels", "Talvez a pele o sinta mais perto dos {feels} graus.");
        put("zen.pt.today", "O dia trará {sky}, entre {min} e {max} graus.");
        put("zen.pt.rainAt", "A chuva pode vir, {chance} por cento de probabilidade, talvez {from}.");
        put("zen.pt.rain", "Há {chance} por cento de probabilidade de chuva. Deixa estar.");
        put("zen.pt.rainLow", "Apenas uma pequena probabilidade de chuva, {chance} por cento.");
        put("zen.pt.dry", "Sem chuva hoje. Um céu aberto e seco.");
        put("zen.pt.umbrella", "Talvez leves um guarda-chuva, por precaução.");
        put("zen.pt.cold", "Está frio. Envolve-te em algo quente.");
        put("zen.pt.jacket", "Um casaco leve vai deixar-te confortável.");
        put("zen.pt.hot", "Vai estar calor. Bebe água, e procura sombra.");
        put("zen.pt.uv", "O sol está forte, índice UV {uv}. Cuida da tua pele.");
        put("zen.pt.wind", "O vento vai estar forte, até {wind} quilómetros por hora. Move-te com ele.");
        put("zen.pt.sunset", "O sol põe-se {at}.");
        put("zen.pt.agenda", "Hoje tens {count}.", "O teu dia guarda {count}.");
        put("zen.pt.empty", "A tua agenda está vazia hoje. Espaço para respirar.");
        put("zen.pt.allDay", "Todo o dia, {title}.");
        put("zen.pt.event", "{at}, {title}.");
        put("zen.pt.eventAt", "{at}, {title}, em {place}.");
        put("zen.pt.more", "E mais {n}. Uma coisa de cada vez.");
        put("zen.pt.first", "O primeiro momento do teu dia começa daqui a {in}. Há tempo.");
        put("zen.pt.todo", "Coisas que quiseste lembrar.");
        put("zen.pt.news", "Algumas coisas que acontecem no mundo.", "Algumas notícias, para receber com leveza.");
        put("zen.pt.outro",
                "É tudo. Entra no teu dia com calma, {name}.",
                "Tem um bom dia, {name}.");
        put("zen.pt.noCity", "Ainda não sei onde estás. Quando quiseres, define a tua cidade na aplicação.");
        put("zen.pt.noWeather", "O tempo ficou fora de alcance esta manhã. Não faz mal.");
        put("zen.pt.noCalendar", "Ainda não consigo ver o teu calendário. Podes autorizar na aplicação.");
        put("zen.pt.noNews", "Não chegaram notícias esta manhã. Talvez seja uma dádiva.");
    }

    // ------------------------------------------------------------------ build

    private final boolean pt;
    private final String persona;
    private final Random rnd;
    private final Map<String, String> vars = new HashMap<>();
    private final List<Line> out = new ArrayList<>();

    private ScriptWriter(String lang, String persona, Random rnd) {
        this.pt = "pt".equals(lang);
        this.persona = T.containsKey(persona + ".en.greet") ? persona : "butler";
        this.rnd = rnd;
    }

    static List<Line> build(Prefs p, Data d) {
        ScriptWriter w = new ScriptWriter(p.lang(), p.persona(), new Random());
        return w.write(p, d);
    }

    private List<Line> write(Prefs p, Data d) {
        long now = System.currentTimeMillis();
        initVars(p.name(), now);

        say(pick("greet"), 700);

        // Weather
        say(pick("weather"), 300);
        if (!p.hasCity()) {
            say(pick("noCity"), 600);
        } else if (d.weather == null) {
            say(pick("noWeather"), 600);
        } else {
            weather(d.weather);
        }

        // Agenda
        if (d.noCalendarPermission) {
            say(pick("noCalendar"), 600);
        } else if (d.agenda != null) {
            agenda(d.agenda, now);
        }

        // To-dos
        List<String> todos = new ArrayList<>();
        for (String t : p.todos().split("\\n")) if (!t.trim().isEmpty()) todos.add(t.trim());
        if (!todos.isEmpty()) {
            say(pick("todo"), 300);
            for (String t : todos) say(t, 350);
            pause(400);
        }

        // News
        if (p.headlines() > 0) {
            if (d.news == null) {
                say(pick("noNews"), 600);
            } else if (!d.news.isEmpty()) {
                say(pick("news"), 400);
                // Each headline in its own language's voice; unclear ones stay in yours.
                for (String h : d.news) say(h, 550, LanguageGuesser.pick(h, lang()));
            }
        }

        say(pick("outro"), 0);
        return out;
    }

    private void initVars(String name, long now) {
        vars.put("name", name == null || name.trim().isEmpty() ? defaultAddress(persona, pt) : name.trim());
        vars.put("time", time(now));
        vars.put("day", pt
                ? new SimpleDateFormat("EEEE, d 'de' MMMM", PT).format(new Date(now))
                : new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(new Date(now)));
    }

    private void weather(WeatherClient.Weather w) {
        int temp = (int) Math.round(w.temp);
        int feels = (int) Math.round(w.feelsLike);
        int max = (int) Math.round(w.max);
        int min = (int) Math.round(w.min);

        String nowLine = line("now", "temp", temp, "sky", wmo(w.code));
        if (Math.abs(feels - temp) >= 3) nowLine += " " + line("feels", "feels", feels);
        say(nowLine, 250);
        say(line("today", "sky", wmo(w.dayCode), "max", max, "min", min), 250);

        boolean wet = w.rainChance >= 50 || w.rainMm >= 1.0;
        vars.put("chance", String.valueOf(w.rainChance));
        if (wet && w.rainFromMinute >= 0) {
            setTimeVars(atMinute(w.rainFromMinute));
            say(pick("rainAt"), 250);
        } else if (wet) {
            say(pick("rain"), 250);
        } else if (w.rainChance >= 20) {
            say(pick("rainLow"), 250);
        } else {
            say(pick("dry"), 250);
        }

        // Decision helpers
        if (wet) say(pick("umbrella"), 250);
        if (max < 12) say(pick("cold"), 250);
        else if (max < 19 || min < 10) say(pick("jacket"), 250);
        if (max >= 30) say(pick("hot"), 250);
        if (w.uvMax >= 6) say(line("uv", "uv", (int) Math.round(w.uvMax)), 250);
        if (w.windMax >= 40) say(line("wind", "wind", (int) Math.round(w.windMax)), 250);

        if (w.sunsetMinute >= 0) {
            setTimeVars(atMinute(w.sunsetMinute));
            say(pick("sunset"), 700);
        } else {
            pause(700);
        }
    }

    private void agenda(List<AgendaReader.Event> events, long now) {
        if (events.isEmpty()) {
            say(pick("empty"), 600);
            return;
        }
        int n = events.size();
        vars.put("count", n == 1
                ? tr("one event", "um compromisso")
                : n + tr(" events", " compromissos"));
        say(pick("agenda"), 350);

        int shown = Math.min(n, 8);
        for (int i = 0; i < shown; i++) {
            AgendaReader.Event e = events.get(i);
            vars.put("title", e.title.isEmpty() ? tr("an untitled event", "um evento sem título") : e.title);
            String loc = shortLocation(e.location);
            if (e.allDay) {
                say(pick("allDay"), 350);
                continue;
            }
            setTimeVars(e.begin);
            if (loc.isEmpty()) {
                say(pick("event"), 350);
            } else {
                vars.put("place", loc);
                say(pick("eventAt"), 350);
            }
        }
        if (n > shown) say(line("more", "n", n - shown), 350);

        for (AgendaReader.Event e : events) {
            if (e.allDay || e.begin <= now) continue;
            long gap = e.begin - now;
            if (gap < 14L * 60 * 60 * 1000) say(line("first", "in", duration(gap)), 300);
            break;
        }
        pause(500);
    }

    // ------------------------------------------------------------------ helpers

    private void say(String text, int pauseAfterMs) {
        say(text, pauseAfterMs, lang());
    }

    private void say(String text, int pauseAfterMs, String lang) {
        if (text == null || text.isEmpty()) return;
        out.add(new Line(text, pauseAfterMs, lang));
    }

    private String lang() {
        return pt ? "pt" : "en";
    }

    private void pause(int ms) {
        if (!out.isEmpty()) {
            Line last = out.remove(out.size() - 1);
            out.add(new Line(last.text, Math.max(last.pauseAfterMs, ms), last.lang));
        }
    }

    /** Sets the given placeholders (name, value, name, value…) and picks a line for the slot. */
    private String line(String slot, Object... nameValues) {
        for (int i = 0; i + 1 < nameValues.length; i += 2) {
            vars.put((String) nameValues[i], String.valueOf(nameValues[i + 1]));
        }
        return pick(slot);
    }

    private String pick(String slot) {
        String lang = pt ? ".pt." : ".en.";
        String[] v = T.get(persona + lang + slot);
        if (v == null || v.length == 0) v = T.get("butler" + lang + slot); // every persona should have it
        if (v == null || v.length == 0) return "";
        String s = v[rnd.nextInt(v.length)];
        for (Map.Entry<String, String> e : vars.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue());
        }
        return s;
    }

    /** {at}, and in Portuguese {from}, for a clock time. */
    private void setTimeVars(long millis) {
        String t = time(millis);
        if (!pt) {
            vars.put("at", t);
            vars.put("from", t);
            return;
        }
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        int h = c.get(Calendar.HOUR_OF_DAY);
        int m = c.get(Calendar.MINUTE);
        if (h == 12 && m == 0) {
            vars.put("at", "ao " + t);
            vars.put("from", "a partir do " + t);
        } else if (h == 0 || h == 1) {           // "à meia-noite", "à 1 e 30"
            vars.put("at", "à " + t);
            vars.put("from", "a partir da " + t);
        } else {
            vars.put("at", "às " + t);
            vars.put("from", "a partir das " + t);
        }
    }

    private String tr(String en, String ptText) {
        return pt ? ptText : en;
    }

    private static long atMinute(int minuteOfDay) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        c.set(Calendar.MINUTE, minuteOfDay % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** A time the speech engine reads naturally: "7:30 AM" / "7 e 30". */
    private String time(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        int h = c.get(Calendar.HOUR_OF_DAY);
        int m = c.get(Calendar.MINUTE);
        if (!pt) {
            return new SimpleDateFormat(m == 0 ? "h a" : "h:mm a", Locale.US).format(new Date(millis));
        }
        if (m == 0) {
            if (h == 0) return "meia-noite";
            if (h == 12) return "meio-dia";
            return h + (h == 1 ? " hora" : " horas");
        }
        return h + " e " + m;
    }

    private String duration(long ms) {
        long mins = Math.max(1, Math.round(ms / 60000.0));
        long h = mins / 60;
        long m = mins % 60;
        String hs = h == 1 ? tr("1 hour", "1 hora") : h + tr(" hours", " horas");
        String ms2 = m == 1 ? tr("1 minute", "1 minuto") : m + tr(" minutes", " minutos");
        if (h == 0) return ms2;
        if (m == 0) return hs;
        return hs + tr(" and ", " e ") + ms2;
    }

    /** "Rua de Exemplo 12, 4200-000 Porto, Portugal" -> "Rua de Exemplo 12" */
    private static String shortLocation(String loc) {
        if (loc == null) return "";
        int comma = loc.indexOf(',');
        String s = comma > 0 ? loc.substring(0, comma) : loc;
        return s.length() > 60 ? "" : s.trim();
    }

    /** WMO weather codes as used by Open-Meteo. */
    private String wmo(int code) {
        switch (code) {
            case 0: return tr("clear skies", "céu limpo");
            case 1: return tr("mostly clear skies", "céu pouco nublado");
            case 2: return tr("some clouds", "céu parcialmente nublado");
            case 3: return tr("overcast skies", "céu encoberto");
            case 45: case 48: return tr("fog", "nevoeiro");
            case 51: case 53: case 55: return tr("drizzle", "chuvisco");
            case 56: case 57: return tr("freezing drizzle", "chuvisco gelado");
            case 61: return tr("light rain", "chuva fraca");
            case 63: return tr("rain", "chuva");
            case 65: return tr("heavy rain", "chuva forte");
            case 66: case 67: return tr("freezing rain", "chuva gelada");
            case 71: return tr("light snow", "neve fraca");
            case 73: case 77: return tr("snow", "neve");
            case 75: return tr("heavy snow", "neve forte");
            case 80: return tr("light showers", "aguaceiros fracos");
            case 81: return tr("rain showers", "aguaceiros");
            case 82: return tr("violent showers", "aguaceiros fortes");
            case 85: case 86: return tr("snow showers", "aguaceiros de neve");
            case 95: return tr("thunderstorms", "trovoada");
            case 96: case 99: return tr("thunderstorms with hail", "trovoada com granizo");
            default: return tr("mixed conditions", "condições variáveis");
        }
    }
}
