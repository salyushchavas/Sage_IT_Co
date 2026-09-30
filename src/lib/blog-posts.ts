// Blog posts shown on /blogs. `id` is the URL slug: /blogs/<id>.
export interface BlogPost {
  id: string;
  title: string;
  excerpt: string;
  category: string;
  /** ISO date, shown as "September 30, 2026". */
  date: string;
  readMinutes: number;
  sections: { heading?: string; paragraphs: string[] }[];
}

export const blogPosts: BlogPost[] = [
  {
    id: "choosing-your-first-technology-course",
    title: "How to Choose Your First Technology Course",
    excerpt:
      "With so many directions to go in, picking a starting point can feel harder than the learning itself. Three questions make the choice simpler.",
    category: "Getting Started",
    date: "2026-09-30",
    readMinutes: 4,
    sections: [
      {
        paragraphs: [
          "Cloud, data, security, web development: every area of technology promises a rewarding career, and each one has more courses than anyone could finish. The result is that many people spend weeks comparing options and never actually begin.",
          "You do not need the perfect course. You need a good first one. These three questions will get you there.",
        ],
      },
      {
        heading: "1. What do you already enjoy doing?",
        paragraphs: [
          "If you like solving puzzles and finding what is broken, security and testing reward that instinct. If you like building things people can see and use, web development gives quick, visible results. If you are the person who reaches for a spreadsheet, data work will feel familiar.",
          "Starting close to what you already enjoy keeps you going through the weeks when the material gets hard.",
        ],
      },
      {
        heading: "2. How much time can you honestly give each week?",
        paragraphs: [
          "A course you can follow steadily beats an ambitious one you abandon. Look at the number of modules and lessons, then divide by the hours you really have, not the hours you wish you had.",
          "If the answer is four hours a week, choose a beginner course with short lessons and plan to finish it before adding anything else.",
        ],
      },
      {
        heading: "3. What will you be able to show at the end?",
        paragraphs: [
          "The best first course leaves you with something concrete: completed assignments, a small project, a certificate you can share. Those are what turn \"I studied this\" into \"here is what I made\".",
          "Pick the course that answers all three questions well enough, start this week, and adjust as you learn more about what you like.",
        ],
      },
    ],
  },
  {
    id: "habits-that-make-online-learning-stick",
    title: "Five Habits That Make Online Learning Stick",
    excerpt:
      "Learning online gives you freedom, and freedom is easy to waste. These five small habits keep progress steady without taking over your week.",
    category: "Study Skills",
    date: "2026-09-30",
    readMinutes: 5,
    sections: [
      {
        paragraphs: [
          "Nobody falls behind in an online course because a single lesson was too difficult. They fall behind because a busy week turns into two, and picking things back up starts to feel like a chore. Habits are what protect you from that.",
        ],
      },
      {
        heading: "1. Fix a time, not a target",
        paragraphs: [
          "\"I'll finish module three this week\" is easy to push to Sunday night. \"Tuesday and Thursday, 7 to 8 pm\" is a lot harder to skip. Put the sessions in your calendar like any other appointment.",
        ],
      },
      {
        heading: "2. Keep sessions short",
        paragraphs: [
          "Forty-five focused minutes teach more than three distracted hours. Stop while you still have energy, and you will be glad to come back the next day.",
        ],
      },
      {
        heading: "3. Do the practical work straight away",
        paragraphs: [
          "Finish a lesson, then do its quiz or assignment before you move on. Using an idea within minutes of hearing it is the single most reliable way to remember it.",
        ],
      },
      {
        heading: "4. Write down one thing you learned",
        paragraphs: [
          "One sentence, in your own words, at the end of every session. Over a few months it becomes a record of how far you have come, and a useful set of notes before an interview.",
        ],
      },
      {
        heading: "5. Protect the streak, not the pace",
        paragraphs: [
          "Some days you will only manage ten minutes. Take them. A small session keeps the routine alive, and the routine is what carries you to the end of the course.",
        ],
      },
    ],
  },
  {
    id: "turning-new-skills-into-interview-stories",
    title: "Turning New Skills Into Interview Stories",
    excerpt:
      "Finishing a course is one thing. Explaining what you can now do, in a way an interviewer remembers, is another. Here is a simple way to prepare.",
    category: "Career",
    date: "2026-09-30",
    readMinutes: 4,
    sections: [
      {
        paragraphs: [
          "Interviewers rarely ask you to list what you studied. They ask what you have done, what went wrong, and what you would do differently. People who have just finished training often have good answers to all three and no practice saying them out loud.",
        ],
      },
      {
        heading: "Start from the work, not the syllabus",
        paragraphs: [
          "Go back through your assignments and projects and pick three that you remember well. For each one, write down what you were asked to do, what you decided, and what happened.",
          "That short record is the raw material for almost every interview question you will meet.",
        ],
      },
      {
        heading: "Give each story a shape",
        paragraphs: [
          "A story that lands has four parts: the situation, the task in front of you, the action you took, and the result. Keep the first two brief and spend most of your time on what you actually did.",
          "If something failed along the way, leave it in. How you noticed the problem and fixed it is usually the most convincing part.",
        ],
      },
      {
        heading: "Match the story to the role",
        paragraphs: [
          "Read the job description and underline the skills it repeats. Then decide which of your stories shows each one. The same project can show problem solving in one interview and attention to detail in another.",
        ],
      },
      {
        heading: "Practise out loud",
        paragraphs: [
          "Two minutes per story is about right. Say each one aloud until it sounds like you talking, not like something memorised. If you have a coach or a friend who will listen, ask them what they remember afterwards. That is what the interviewer will remember too.",
        ],
      },
    ],
  },
];

export function formatPostDate(iso: string): string {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString("en-US", {
    year: "numeric",
    month: "long",
    day: "numeric",
    timeZone: "UTC",
  });
}
