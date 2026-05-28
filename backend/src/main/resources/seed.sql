-- Spire Course Platform - Seed Data
-- Run this AFTER schema.sql

-- ============================================
-- USERS
-- ============================================

-- Admin (admin@spire.dev / admin123)
INSERT INTO users (id, email, password_hash, full_name, avatar_url, bio, role) VALUES
('a0000000-0000-0000-0000-000000000001', 'admin@spire.dev',
 '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
 'Spire Admin', NULL, 'Platform administrator', 'ADMIN');

-- Instructors (password123)
INSERT INTO users (id, email, password_hash, full_name, avatar_url, bio, role) VALUES
('b0000000-0000-0000-0000-000000000001', 'arjun@spire.dev',
 '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG',
 'Arjun Mehta', '/avatars/arjun.jpg',
 'Full-stack developer with 10+ years of experience building scalable web applications.', 'INSTRUCTOR'),

('b0000000-0000-0000-0000-000000000002', 'priya@spire.dev',
 '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG',
 'Priya Sharma', '/avatars/priya.jpg',
 'Data scientist and Python expert. Former ML engineer at a leading tech company.', 'INSTRUCTOR'),

('b0000000-0000-0000-0000-000000000003', 'rahul@spire.dev',
 '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG',
 'Rahul Kapoor', '/avatars/rahul.jpg',
 'Cloud architect and DevOps specialist. AWS certified solutions architect.', 'INSTRUCTOR');

-- ============================================
-- COURSES
-- ============================================

INSERT INTO courses (id, title, slug, description, short_description, thumbnail_url, instructor_id, category, level, price, is_free, duration_hours, lessons_count, enrolled_count, rating, ratings_count, tags, is_published) VALUES

('c0000000-0000-0000-0000-000000000001',
 'Full-Stack Web Development', 'full-stack-web-development',
 'Master modern full-stack web development from scratch. Learn HTML, CSS, JavaScript, React, Node.js, Express, and PostgreSQL to build production-ready applications.',
 'Build complete web apps with React, Node.js and PostgreSQL.',
 '/thumbnails/fullstack.jpg',
 'b0000000-0000-0000-0000-000000000001', 'Web Development', 'BEGINNER',
 0, true, 42.0, 5, 1240, 4.80, 312,
 ARRAY['html','css','javascript','react','nodejs','postgresql'],
 true),

('c0000000-0000-0000-0000-000000000002',
 'React Mastery', 'react-mastery',
 'Deep dive into React including hooks, context, Redux, performance optimization, testing, and building real-world projects with modern patterns.',
 'Advanced React patterns, hooks, and state management.',
 '/thumbnails/react.jpg',
 'b0000000-0000-0000-0000-000000000001', 'Frontend', 'INTERMEDIATE',
 1999, false, 28.5, 4, 870, 4.70, 198,
 ARRAY['react','redux','hooks','typescript','testing'],
 true),

('c0000000-0000-0000-0000-000000000003',
 'Python for Data Science', 'python-for-data-science',
 'Learn Python programming for data analysis, visualization, and machine learning. Covers NumPy, Pandas, Matplotlib, Scikit-learn, and real-world datasets.',
 'Data analysis and ML with Python, Pandas, and Scikit-learn.',
 '/thumbnails/python-ds.jpg',
 'b0000000-0000-0000-0000-000000000002', 'Data Science', 'BEGINNER',
 2499, false, 36.0, 5, 1580, 4.90, 425,
 ARRAY['python','pandas','numpy','matplotlib','scikit-learn','machine-learning'],
 true),

('c0000000-0000-0000-0000-000000000004',
 'Cloud Architecture with AWS', 'cloud-architecture-with-aws',
 'Design and deploy scalable cloud solutions on AWS. Covers EC2, S3, Lambda, API Gateway, DynamoDB, CloudFormation, and architecture best practices.',
 'Design scalable cloud solutions with core AWS services.',
 '/thumbnails/aws.jpg',
 'b0000000-0000-0000-0000-000000000003', 'Cloud Computing', 'ADVANCED',
 3499, false, 32.0, 4, 640, 4.60, 145,
 ARRAY['aws','cloud','devops','serverless','infrastructure'],
 true),

('c0000000-0000-0000-0000-000000000005',
 'UI/UX Design Fundamentals', 'ui-ux-design-fundamentals',
 'Learn the principles of user interface and user experience design. Master Figma, design systems, prototyping, user research, and accessibility.',
 'Design beautiful, user-friendly interfaces with Figma.',
 '/thumbnails/uiux.jpg',
 'b0000000-0000-0000-0000-000000000002', 'Design', 'BEGINNER',
 0, true, 20.0, 4, 960, 4.50, 210,
 ARRAY['figma','ui','ux','design-systems','prototyping','accessibility'],
 true),

('c0000000-0000-0000-0000-000000000006',
 'Mobile App Dev with React Native', 'mobile-app-dev-with-react-native',
 'Build cross-platform mobile applications using React Native. Covers navigation, state management, native modules, animations, and publishing to app stores.',
 'Build iOS and Android apps with a single React Native codebase.',
 '/thumbnails/react-native.jpg',
 'b0000000-0000-0000-0000-000000000001', 'Mobile Development', 'INTERMEDIATE',
 2999, false, 30.0, 5, 720, 4.70, 168,
 ARRAY['react-native','mobile','ios','android','javascript','expo'],
 true);

-- ============================================
-- LESSONS
-- ============================================

-- Full-Stack Web Development (5 lessons)
INSERT INTO lessons (course_id, title, description, video_url, order_index, duration_minutes, is_free) VALUES
('c0000000-0000-0000-0000-000000000001', 'Setting Up Your Development Environment', 'Install Node.js, VS Code, Git, and configure your workspace.', '/videos/fs-01.mp4', 1, 25, true),
('c0000000-0000-0000-0000-000000000001', 'HTML & CSS Foundations', 'Build semantic HTML pages and style them with modern CSS.', '/videos/fs-02.mp4', 2, 55, true),
('c0000000-0000-0000-0000-000000000001', 'JavaScript ES6+ Essentials', 'Arrow functions, destructuring, promises, async/await and modules.', '/videos/fs-03.mp4', 3, 60, false),
('c0000000-0000-0000-0000-000000000001', 'Building REST APIs with Express', 'Create a CRUD API with Express, middleware, and error handling.', '/videos/fs-04.mp4', 4, 50, false),
('c0000000-0000-0000-0000-000000000001', 'React Frontend & Full Integration', 'Connect a React frontend to your API and deploy the full stack.', '/videos/fs-05.mp4', 5, 65, false);

-- React Mastery (4 lessons)
INSERT INTO lessons (course_id, title, description, video_url, order_index, duration_minutes, is_free) VALUES
('c0000000-0000-0000-0000-000000000002', 'Advanced Hooks Patterns', 'Custom hooks, useReducer, useCallback, useMemo deep dive.', '/videos/rm-01.mp4', 1, 45, true),
('c0000000-0000-0000-0000-000000000002', 'State Management with Redux Toolkit', 'Slices, thunks, RTK Query, and when to use global state.', '/videos/rm-02.mp4', 2, 55, false),
('c0000000-0000-0000-0000-000000000002', 'Performance Optimization', 'React Profiler, code splitting, lazy loading, and memoization.', '/videos/rm-03.mp4', 3, 40, false),
('c0000000-0000-0000-0000-000000000002', 'Testing React Applications', 'Unit and integration testing with Jest and React Testing Library.', '/videos/rm-04.mp4', 4, 50, false);

-- Python for Data Science (5 lessons)
INSERT INTO lessons (course_id, title, description, video_url, order_index, duration_minutes, is_free) VALUES
('c0000000-0000-0000-0000-000000000003', 'Python Crash Course', 'Variables, data types, control flow, functions, and OOP basics.', '/videos/py-01.mp4', 1, 50, true),
('c0000000-0000-0000-0000-000000000003', 'Data Manipulation with Pandas', 'DataFrames, filtering, grouping, merging, and cleaning data.', '/videos/py-02.mp4', 2, 60, true),
('c0000000-0000-0000-0000-000000000003', 'Data Visualization with Matplotlib & Seaborn', 'Create charts, plots, and dashboards from real datasets.', '/videos/py-03.mp4', 3, 45, false),
('c0000000-0000-0000-0000-000000000003', 'Intro to Machine Learning with Scikit-learn', 'Regression, classification, model evaluation, and pipelines.', '/videos/py-04.mp4', 4, 55, false),
('c0000000-0000-0000-0000-000000000003', 'Capstone: End-to-End ML Project', 'Build a complete ML pipeline from data collection to deployment.', '/videos/py-05.mp4', 5, 70, false);

-- Cloud Architecture with AWS (4 lessons)
INSERT INTO lessons (course_id, title, description, video_url, order_index, duration_minutes, is_free) VALUES
('c0000000-0000-0000-0000-000000000004', 'AWS Fundamentals & IAM', 'Regions, services overview, IAM users, roles, and policies.', '/videos/aws-01.mp4', 1, 40, true),
('c0000000-0000-0000-0000-000000000004', 'Compute & Storage: EC2, S3, and EBS', 'Launch instances, configure storage, and set up auto-scaling.', '/videos/aws-02.mp4', 2, 55, false),
('c0000000-0000-0000-0000-000000000004', 'Serverless with Lambda & API Gateway', 'Build event-driven architectures without managing servers.', '/videos/aws-03.mp4', 3, 50, false),
('c0000000-0000-0000-0000-000000000004', 'Infrastructure as Code with CloudFormation', 'Define and deploy your entire stack with YAML templates.', '/videos/aws-04.mp4', 4, 45, false);

-- UI/UX Design Fundamentals (4 lessons)
INSERT INTO lessons (course_id, title, description, video_url, order_index, duration_minutes, is_free) VALUES
('c0000000-0000-0000-0000-000000000005', 'Design Thinking & User Research', 'Understand user needs through interviews, personas, and journey maps.', '/videos/ux-01.mp4', 1, 35, true),
('c0000000-0000-0000-0000-000000000005', 'Figma from Zero to Hero', 'Master Figma: frames, components, auto-layout, and prototyping.', '/videos/ux-02.mp4', 2, 50, true),
('c0000000-0000-0000-0000-000000000005', 'Building a Design System', 'Create reusable tokens, components, and documentation.', '/videos/ux-03.mp4', 3, 45, false),
('c0000000-0000-0000-0000-000000000005', 'Accessibility & Inclusive Design', 'WCAG guidelines, color contrast, screen readers, and audits.', '/videos/ux-04.mp4', 4, 40, false);

-- Mobile App Dev with React Native (5 lessons)
INSERT INTO lessons (course_id, title, description, video_url, order_index, duration_minutes, is_free) VALUES
('c0000000-0000-0000-0000-000000000006', 'React Native & Expo Setup', 'Initialize a project with Expo, understand the file structure.', '/videos/rn-01.mp4', 1, 30, true),
('c0000000-0000-0000-0000-000000000006', 'Core Components & Styling', 'View, Text, Image, ScrollView, FlatList, and StyleSheet API.', '/videos/rn-02.mp4', 2, 45, false),
('c0000000-0000-0000-0000-000000000006', 'Navigation with React Navigation', 'Stack, tab, and drawer navigators with deep linking.', '/videos/rn-03.mp4', 3, 50, false),
('c0000000-0000-0000-0000-000000000006', 'Animations & Gestures', 'Animated API, Reanimated, and gesture handlers for fluid UX.', '/videos/rn-04.mp4', 4, 40, false),
('c0000000-0000-0000-0000-000000000006', 'Publishing to App Store & Google Play', 'Build, sign, and submit your app for review.', '/videos/rn-05.mp4', 5, 35, false);

-- ============================================
-- MODULES (course → modules → lessons)
-- ============================================

INSERT INTO modules (id, course_id, title, description, order_index) VALUES
-- Full-Stack Web Development
('d0000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001', 'Setup & Frontend', 'Get your environment ready and build your first frontend pages.', 0),
('d0000000-0000-0000-0000-000000000002', 'c0000000-0000-0000-0000-000000000001', 'Backend with Express', 'Build a REST API with Node.js and Express.', 1),
('d0000000-0000-0000-0000-000000000003', 'c0000000-0000-0000-0000-000000000001', 'Full-Stack Integration', 'Connect frontend and backend; deploy the full stack.', 2),
-- React Mastery
('d0000000-0000-0000-0000-000000000004', 'c0000000-0000-0000-0000-000000000002', 'Advanced Patterns', 'Custom hooks and composition strategies.', 0),
('d0000000-0000-0000-0000-000000000005', 'c0000000-0000-0000-0000-000000000002', 'State & Performance', 'Manage state with Redux Toolkit and profile to optimize.', 1),
('d0000000-0000-0000-0000-000000000006', 'c0000000-0000-0000-0000-000000000002', 'Testing', 'Unit and integration testing for React apps.', 2),
-- Python for Data Science
('d0000000-0000-0000-0000-000000000007', 'c0000000-0000-0000-0000-000000000003', 'Python Foundations', 'Python basics and data manipulation with Pandas.', 0),
('d0000000-0000-0000-0000-000000000008', 'c0000000-0000-0000-0000-000000000003', 'Data Visualization', 'Create charts and plots from real datasets.', 1),
('d0000000-0000-0000-0000-000000000009', 'c0000000-0000-0000-0000-000000000003', 'Machine Learning', 'From your first scikit-learn model to an end-to-end capstone.', 2),
-- Cloud Architecture with AWS
('d0000000-0000-0000-0000-00000000000a', 'c0000000-0000-0000-0000-000000000004', 'AWS Foundations', 'Get oriented in AWS and configure IAM securely.', 0),
('d0000000-0000-0000-0000-00000000000b', 'c0000000-0000-0000-0000-000000000004', 'Core Services', 'Compute, storage, and serverless workhorses.', 1),
('d0000000-0000-0000-0000-00000000000c', 'c0000000-0000-0000-0000-000000000004', 'Infrastructure as Code', 'Define your stack with CloudFormation.', 2),
-- UI/UX Design Fundamentals
('d0000000-0000-0000-0000-00000000000d', 'c0000000-0000-0000-0000-000000000005', 'UX Foundations', 'Design thinking and Figma fundamentals.', 0),
('d0000000-0000-0000-0000-00000000000e', 'c0000000-0000-0000-0000-000000000005', 'Design Systems', 'Build reusable components, tokens, and documentation.', 1),
('d0000000-0000-0000-0000-00000000000f', 'c0000000-0000-0000-0000-000000000005', 'Accessibility', 'WCAG and inclusive design practices.', 2),
-- Mobile App Dev with React Native
('d0000000-0000-0000-0000-000000000010', 'c0000000-0000-0000-0000-000000000006', 'Setup & Components', 'Expo, project structure, core components, and styling.', 0),
('d0000000-0000-0000-0000-000000000011', 'c0000000-0000-0000-0000-000000000006', 'Navigation & Polish', 'React Navigation, animations, and gestures.', 1),
('d0000000-0000-0000-0000-000000000012', 'c0000000-0000-0000-0000-000000000006', 'Shipping', 'Build, sign, and publish to the stores.', 2);

-- Assign existing lessons to their modules
UPDATE lessons SET module_id = 'd0000000-0000-0000-0000-000000000001' WHERE course_id = 'c0000000-0000-0000-0000-000000000001' AND order_index IN (1, 2, 3);
UPDATE lessons SET module_id = 'd0000000-0000-0000-0000-000000000002' WHERE course_id = 'c0000000-0000-0000-0000-000000000001' AND order_index = 4;
UPDATE lessons SET module_id = 'd0000000-0000-0000-0000-000000000003' WHERE course_id = 'c0000000-0000-0000-0000-000000000001' AND order_index = 5;
UPDATE lessons SET module_id = 'd0000000-0000-0000-0000-000000000004' WHERE course_id = 'c0000000-0000-0000-0000-000000000002' AND order_index = 1;
UPDATE lessons SET module_id = 'd0000000-0000-0000-0000-000000000005' WHERE course_id = 'c0000000-0000-0000-0000-000000000002' AND order_index IN (2, 3);
UPDATE lessons SET module_id = 'd0000000-0000-0000-0000-000000000006' WHERE course_id = 'c0000000-0000-0000-0000-000000000002' AND order_index = 4;
UPDATE lessons SET module_id = 'd0000000-0000-0000-0000-000000000007' WHERE course_id = 'c0000000-0000-0000-0000-000000000003' AND order_index IN (1, 2);
UPDATE lessons SET module_id = 'd0000000-0000-0000-0000-000000000008' WHERE course_id = 'c0000000-0000-0000-0000-000000000003' AND order_index = 3;
UPDATE lessons SET module_id = 'd0000000-0000-0000-0000-000000000009' WHERE course_id = 'c0000000-0000-0000-0000-000000000003' AND order_index IN (4, 5);
UPDATE lessons SET module_id = 'd0000000-0000-0000-0000-00000000000a' WHERE course_id = 'c0000000-0000-0000-0000-000000000004' AND order_index = 1;
UPDATE lessons SET module_id = 'd0000000-0000-0000-0000-00000000000b' WHERE course_id = 'c0000000-0000-0000-0000-000000000004' AND order_index IN (2, 3);
UPDATE lessons SET module_id = 'd0000000-0000-0000-0000-00000000000c' WHERE course_id = 'c0000000-0000-0000-0000-000000000004' AND order_index = 4;
UPDATE lessons SET module_id = 'd0000000-0000-0000-0000-00000000000d' WHERE course_id = 'c0000000-0000-0000-0000-000000000005' AND order_index IN (1, 2);
UPDATE lessons SET module_id = 'd0000000-0000-0000-0000-00000000000e' WHERE course_id = 'c0000000-0000-0000-0000-000000000005' AND order_index = 3;
UPDATE lessons SET module_id = 'd0000000-0000-0000-0000-00000000000f' WHERE course_id = 'c0000000-0000-0000-0000-000000000005' AND order_index = 4;
UPDATE lessons SET module_id = 'd0000000-0000-0000-0000-000000000010' WHERE course_id = 'c0000000-0000-0000-0000-000000000006' AND order_index IN (1, 2);
UPDATE lessons SET module_id = 'd0000000-0000-0000-0000-000000000011' WHERE course_id = 'c0000000-0000-0000-0000-000000000006' AND order_index IN (3, 4);
UPDATE lessons SET module_id = 'd0000000-0000-0000-0000-000000000012' WHERE course_id = 'c0000000-0000-0000-0000-000000000006' AND order_index = 5;

-- ============================================
-- ACHIEVEMENTS / BADGES
-- ============================================

INSERT INTO achievements (user_id, badge_name, badge_icon) VALUES
('a0000000-0000-0000-0000-000000000001', 'Platform Pioneer', 'rocket'),
('a0000000-0000-0000-0000-000000000001', 'Admin Extraordinaire', 'shield'),
('b0000000-0000-0000-0000-000000000001', 'Course Creator', 'book'),
('b0000000-0000-0000-0000-000000000001', 'Top Rated Instructor', 'star'),
('b0000000-0000-0000-0000-000000000002', 'Course Creator', 'book'),
('b0000000-0000-0000-0000-000000000002', 'Data Guru', 'chart-bar'),
('b0000000-0000-0000-0000-000000000003', 'Course Creator', 'book'),
('b0000000-0000-0000-0000-000000000003', 'Cloud Master', 'cloud');
