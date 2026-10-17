package campfire.messages;

import java.util.List;

/// `Sound`: the `/play <name>` sounds, each with a text or an image to show beside the button.
public record Sound(String name, String text, Image image) {
    public record Image(String name, int width, int height) {
        /// `"sounds/#{name}"`
        public String assetPath() {
            return "sounds/" + name;
        }
    }

    /// `"#{name}.mp3"`
    public String assetPath() {
        return name + ".mp3";
    }

    private static Sound text(String name, String text) {
        return new Sound(name, text, null);
    }

    private static Sound image(String name, String file, int width, int height) {
        return new Sound(name, null, new Image(file, width, height));
    }

    public static final List<Sound> BUILTIN = List.of(
        image("56k", "56k.webp", 79, 33),
        text("bell", "🔔"),
        text("bezos", "😆💭"),
        text("bueller", "anyone?"),
        text("butts", "👐 🚬"),
        image("clowntown", "clowntown.webp", 210, 150),
        text("cottoneyejoe", "🎶🙉🎶 "),
        text("crickets", "hears crickets chirping"),
        image("curb", "curb.webp", 150, 101),
        text("dadgummit", "dad gummit!! 🎣"),
        image("dangerzone", "dangerzone.webp", 157, 32),
        text("danielsan", "🎆 🏆 🎆"),
        image("deeper", "top.webp", 188, 80),
        text("ballmer", "developers!"),
        image("donotwant", "donotwant.webp", 150, 150),
        image("drama", "drama.webp", 300, 16),
        text("flawless", "#flawless"),
        text("glados", "🤖💢"),
        text("gogogo", "Go, go, go!"),
        image("greatjob", "greatjob.webp", 79, 16),
        text("greyjoy", "😖🎺"),
        text("guarantee", "guarantees it 👌"),
        text("heygirl", "✨💁✨"),
        text("honk", "HONK"),
        text("horn", "🐶 ✂️ 🐱"),
        text("horror", "💀 💀 💀 💀 💀 💀 💀"),
        text("inconceivable", "doesn't think it means what you think it means…"),
        text("letitgo", "❄️👩❄️⛄️❄️"),
        text("live", "is DOING IT LIVE"),
        image("loggins", "loggins.webp", 200, 151),
        text("makeitso", "make it so 👉"),
        text("noooo", "👸💀😒"),
        image("nyan", "nyan.webp", 36, 15),
        text("ohmy", "raises an eyebrow 😏"),
        text("ohyeah", "isn't playing by the rules"),
        image("pushit", "pushit.webp", 104, 15),
        text("rimshot", "plays a rimshot"),
        text("rollout", "is rolling out 🚗"),
        image("rumble", "rumble.webp", 220, 150),
        text("sax", "🌇🎷🎶"),
        text("secret", "found a secret area 🔑"),
        text("sexyback", "🔞"),
        text("story", "and now you know…"),
        text("tada", "plays a fanfare 🎏"),
        text("tmyk", "✨ ⭐️ The More You Know ✨ ⭐️"),
        text("totes", "😁👍"),
        text("trololo", "трололо"),
        text("trombone", "plays a sad trombone"),
        text("unix", "knows this 💻"),
        text("vuvuzela", "======<() ~ ♪ ~♫"),
        image("what", "what.webp", 100, 131),
        text("whoomp", "👏‼️😎"),
        text("wups", "wups!"),
        image("yay", "yay.webp", 103, 50),
        image("yeah", "yeah.webp", 104, 15),
        text("yodel", "📣🗻🙉")
    );

    public static Sound findByName(String name) {
        for (Sound sound : BUILTIN) if (sound.name.equals(name)) return sound;
        return null;
    }

    /// `Sound.names`, sorted.
    public static List<String> names() {
        return BUILTIN.stream().map(Sound::name).sorted().toList();
    }

    /// The sound a `/play <name>` message plays (ASCII word characters, nothing after), or null.
    public static Sound in(String plainText) {
        if (plainText == null || !plainText.startsWith("/play ")) return null;
        String name = plainText.substring(6);
        if (name.isEmpty()) return null;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c < 128 && (Character.isLetterOrDigit(c) || c == '_'))) return null;
        }
        return findByName(name);
    }
}
